// 10-move.js — движение: moveTo (с обходом препятствий) и go (бег с боями и лутом).
Object.assign(bot, {
  // бег к точке (координаты зоны 0–100); останавливается при бое
  // обход препятствий: прыжок, затем шаг назад + поворот + пробежка в сторону
  // scan=true (гринд): на бегу раз в 1.5 с жмём Tab; подходящая цель в дальности выстрела —
  // останавливаемся и возвращаем 'цель' (бой начнём издалека, а не когда моб добежит вплотную).
  // Память мест, где бот упирался в препятствие (только в браузере, переживает «перезагрузи бота», не переживает F5).
  // Точка = {zone, x, y, n, at}; n — число отдельных случаев (повторы подряд за 20 с считаются одним). Надёжной считаем n ≥ 2.
  stuckList() { return (window.__stuckPoints = window.__stuckPoints || []); },
  noteStuck(s, why) {
    try {
      if (!s || !s.zone || (s.x === 0 && s.y === 0)) return;
      const list = this.stuckList(), now = Date.now();
      const p = list.find(q => q.zone === s.zone && Math.hypot(q.x - s.x, q.y - s.y) < 0.06);
      if (p) { if (now - p.at > 20000) p.n++; p.at = now; p.x = +((p.x + s.x) / 2).toFixed(3); p.y = +((p.y + s.y) / 2).toFixed(3); }
      else { list.push({ zone: s.zone, x: s.x, y: s.y, n: 1, at: now }); if (list.length > 200) list.shift(); }
      if (this.log) this.log('info', 'stuck', 'упор: ' + why, { why, n: (p || list[list.length - 1]).n, facing: s.facing });
    } catch (e) { /* память не должна ломать бег */ }
  },
  // Надёжная точка упора прямо по курсу (до 0.15 карты ≈ 7 м, в пределах ±40° от направления бега) — ближайшая или null
  stuckAhead(s) {
    const list = window.__stuckPoints;
    if (!list || !list.length || !s || !s.zone) return null;
    const norm = a => Math.atan2(Math.sin(a), Math.cos(a));
    let best = null;
    for (const p of list) {
      if (p.zone !== s.zone || p.n < 2) continue;
      const dx = p.x - s.x, dy = p.y - s.y, d = Math.hypot(dx, dy);
      if (d > 0.15 || d < 0.01) continue;
      const diff = norm(Math.atan2(-dx, -dy) - s.facing);          // > 0 — точка левее курса
      if (Math.abs(diff) > 0.7) continue;
      if (!best || d < best.d) best = { p, d, diff };
    }
    return best;
  },
  // Обход: поворот в сторону от точки на ~60° и пробег ~0.2 карты. ctx.n — сколько обходов уже было (не больше 2 за подход).
  // Вызывающий после обхода сам снова наводится на цель. true — обход выполнен.
  async avoidStuck(s, ctx) {
    if (ctx.n >= 2) return false;
    const a = this.stuckAhead(s);
    if (!a) return false;
    ctx.n++;
    const norm = x => Math.atan2(Math.sin(x), Math.cos(x));
    const turnKey = a.diff > 0 ? 'D' : 'A';                          // точка слева — уходим вправо (D), и наоборот
    const f0 = s.facing, t0 = Date.now();
    this.key('W', false);
    this.key(turnKey, true);
    try {
      while (!(ctx.abort && ctx.abort()) && Date.now() - t0 < 1500 && Math.abs(norm(this.state().facing - f0)) < 1.0) await sleep(50);
    } finally { this.key(turnKey, false); }
    const p0 = this.state(), r0 = Date.now();
    this.key('W', true);
    try {
      while (!(ctx.abort && ctx.abort()) && Date.now() - r0 < 2000) {
        const c = this.state();
        if (c.fight || Math.hypot(c.x - p0.x, c.y - p0.y) >= 0.2) break;
        await sleep(100);
      }
    } finally { this.key('W', false); }
    if (this.log) this.log('info', 'approach', 'обход точки упора', { stuckX: a.p.x, stuckY: a.p.y, n: a.p.n, dist: +a.d.toFixed(2), side: turnKey, turnedMs: r0 - t0 });
    return true;
  },

  async moveTo(tx, ty, { tol = 0.3, timeout = 90000, scan = false } = {}) {
    const norm = a => Math.atan2(Math.sin(a), Math.cos(a));
    this.running = true;
    let stuck = 0, side = 1, last = this.state(), lastMove = Date.now();
    const t0 = Date.now();
    let lastTab = 0, tabAt = 0;
    const avoid = { n: 0, abort: () => !this.running };

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
          const st = this.state(), cc = this.combatCfg && this.combatCfg();
          // стрелок: цель в дальности выстрела; ближник: подходящая цель ближе ~28 ярдов (дальше approach ненадёжен)
          const good = this.goodPullTarget(st, { needRange: true });
          if (good && ((cc && cc.ranged) || (st.tDist & (this.mounted && this.mounted() ? 2 : 1)))) {   // верхом едем, пока моб не ближе ~11 ярдов
            if (this.grindStats) this.grindStats.runFound = (this.grindStats.runFound || 0) + 1;
            this.log && this.log('info', 'approach', 'цель найдена на бегу', { onRun: true, tDist: st.tDist, tLvl: st.tLvl });
            return done('цель');
          }
          if (good && this.grindStats) this.grindStats.runFar = (this.grindStats.runFar || 0) + 1;   // подходящая, но дальше ~28 ярдов
        } else if (!tabAt && now - lastTab > 1500) {
          lastTab = tabAt = now;
          await this.tap('TAB');
          if (this.grindStats) this.grindStats.runTabs = (this.grindStats.runTabs || 0) + 1;
        }
      }
      const dx = tx - s.x, dy = ty - s.y;
      if (Math.hypot(dx, dy) < tol) return done('дошёл');
      if (Date.now() - t0 > timeout) return done('таймаут');
      // впереди запомненное место, где уже упирались — обходим сбоку (до 2 раз за вызов)
      if (Math.abs(norm(Math.atan2(-dx, -dy) - s.facing)) < 0.6 && await this.avoidStuck(s, avoid)) {
        last = this.state(); lastMove = Date.now(); continue;
      }

      // 0 = север, угол растёт против часовой (как GetPlayerFacing)
      const err = norm(Math.atan2(-dx, -dy) - s.facing);
      this.key('A', err > 0.08);
      this.key('D', err < -0.08);
      this.key('W', Math.abs(err) < 0.6);

      if (Math.hypot(s.x - last.x, s.y - last.y) > 0.05) { last = s; lastMove = Date.now(); }
      else if (this.held.W && Date.now() - lastMove > 1500) {
        // упёрлись
        this.noteStuck(s, 'moveTo');
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
