// 10-move.js — движение: moveTo (с обходом препятствий) и go (бег с боями и лутом).
Object.assign(bot, {
  // бег к точке (координаты зоны 0–100); останавливается при бое
  // обход препятствий: прыжок, затем шаг назад + поворот + пробежка в сторону
  // scan=true (стрелок в гринде): на бегу раз в 1.5 с жмём Tab; подходящая цель в дальности выстрела —
  // останавливаемся и возвращаем 'цель' (бой начнём издалека, а не когда моб добежит вплотную).
  async moveTo(tx, ty, { tol = 0.3, timeout = 90000, scan = false } = {}) {
    const norm = a => Math.atan2(Math.sin(a), Math.cos(a));
    this.running = true;
    let stuck = 0, side = 1, last = this.state(), lastMove = Date.now();
    const t0 = Date.now();
    let lastTab = 0, tabAt = 0;

    while (this.running) {
      const s = this.state();
      const done = r => { this.release(); return { result: r, stuckTries: stuck, ...s }; };
      if (s.x === 0 && s.y === 0) return done('нет координат (закрой карту мира)');
      if (s.fight) return done('бой');      // до Tab: иначе Tab на бегу собьёт цель, которая уже бьёт нас
      if (scan) {
        const now = Date.now();
        // дальность в spells() обновляется раз в 0.5 с — проверяем цель через 0.7 с после Tab
        if (tabAt && now - tabAt > 700) {
          tabAt = 0;
          if (this.goodPullTarget(this.state(), { needRange: true })) return done('цель');
        } else if (!tabAt && now - lastTab > 1500) {
          lastTab = tabAt = now;
          await this.tap('TAB');
        }
      }
      const dx = tx - s.x, dy = ty - s.y;
      if (Math.hypot(dx, dy) < tol) return done('дошёл');
      if (Date.now() - t0 > timeout) return done('таймаут');

      // 0 = север, угол растёт против часовой (как GetPlayerFacing)
      const err = norm(Math.atan2(-dx, -dy) - s.facing);
      this.key('A', err > 0.08);
      this.key('D', err < -0.08);
      this.key('W', Math.abs(err) < 0.6);

      if (Math.hypot(s.x - last.x, s.y - last.y) > 0.05) { last = s; lastMove = Date.now(); }
      else if (this.held.W && Date.now() - lastMove > 1500) {
        // упёрлись
        if (++stuck > 6) return done('застрял');
        this.key('W', false); this.key('A', false); this.key('D', false);
        // после stop() (running=false) оставшиеся шаги объезда не выполняем: персонаж не должен бежать дальше
        const step = async (k, ms) => { if (this.running) await this.hold(k, ms); };
        if (stuck === 1) {
          await this.tap('SPACE');                       // сначала просто прыжок (низкий забор, камень)
        } else {
          await step(['S'], 600);                        // шаг назад
          await step([side > 0 ? 'A' : 'D'], 400 + 150 * stuck);   // поворот ~70–180°
          await step(['W'], 1500 + 300 * stuck);         // пробежать в сторону
          if (stuck % 2 === 1) side = -side;             // чередовать лево/право
        }
        last = this.state(); lastMove = Date.now();
      }
      await sleep(100);
    }
    return { result: 'остановлен вручную', ...this.state() };
  },

  // дойти до точки, по пути отбиваясь; после боя при autoLoot собираем добычу
  async go(x, y, { autoLoot = true } = {}) {
    for (let i = 0; i < 10; i++) {
      const r = await this.moveTo(x, y);
      if (r.result !== 'бой') return r;
      const f = await this.fight();
      if (f.result !== 'бой окончен') return f;
      if (autoLoot) {
        const l = await this.loot();
        if (l.result === 'бой во время сбора') {
          const f2 = await this.fight();
          if (f2.result !== 'бой окончен') return f2;
        }
      }
    }
    return { result: 'слишком много боёв по пути', ...this.state() };
  }
});
