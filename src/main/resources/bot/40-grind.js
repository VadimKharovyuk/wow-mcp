// 40-grind.js — цикл гринда: найти моба → подойти → бой → лут → отдых → повтор.
Object.assign(bot, {
  grindCfg: {
    radius: 2.5,        // радиус охоты вокруг стартовой точки (единицы карты)
    minLvlDiff: -5,     // не трогать мобов слабее (серые — без опыта)
    maxLvlDiff: 2,      // и сильнее себя больше чем на 2 уровня
    restBelow: 0.6,     // отдыхать, если HP ниже 60%
    manaBelow: 0.3,    // отдыхать, если мана ниже 30%
    pullKey: '3',       // умение для агро издалека
    healKey: '4',       // лечение заклинанием в отдыхе
    drinkKey: '0',      // питьё в отдыхе
    maxKills: 10,       // 0 = без лимита
    maxMinutes: 30,
  },
  grindStats: null,

  // null — цель подходит; иначе строка-причина отказа
  targetProblem(s) {
    if (!s.target || !s.enemy || s.targetHp === 0) return 'нет цели';
    if (s.tFlags & 1) return 'игрок';
    if (s.tFlags & 2) return 'элита/редкий';
    if (s.tFlags & 4) return 'чужой моб';
    if (s.tLvl > this.grindCfg.maxLvlDiff) return 'слишком сильный';
    if (s.tLvl < this.grindCfg.minLvlDiff) return 'слишком слабый';
    return null;
  },

  // Tab по кругу: 3 нажатия, поворот ~60°, и так 6 раз (полный оборот)
  async findTarget() {
    for (let turn = 0; turn < 2 && this.grinding; turn++) {
      for (let i = 0; i < 3 && this.grinding; i++) {
        await this.tap('TAB'); await sleep(250);
        const s = this.state();
        if (!this.targetProblem(s)) return s;
      }
      await this.hold('A', 330); await sleep(150);
    }
    if (!this.grinding) return null;
    const r = await this.lookAround({ minLvlDiff: this.grindCfg.minLvlDiff, maxLvlDiff: this.grindCfg.maxLvlDiff });
    if (r && r.found) return this.state();
    await chat('/cleartarget');
    return null;
  },

  // подойти, пока умение агро не станет доступно (цель в его радиусе)
  async approach(maxMs = 6000) {
    const isReady = k => (this.state().ready >> (+k - 1)) & 1;
    const t0 = Date.now();
    this.key('W', true);
    while (this.grinding && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2)) break;
      await sleep(150);
    }
    this.key('W', false);
    const s = this.state();
    return s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2);
  },

  // бой + лут; возвращает null если всё ок, иначе причину для выхода из гринда
  async fightAndLoot(st) {
    const f = await this.fight();
    if (!this.grinding) return 'остановлен вручную';
    if (f.result !== 'бой окончен') return 'бой: ' + f.result;
    st.kills++;
    const l = await this.loot();
    if (l.result === 'добыча собрана') st.loots++;
    else if (l.result.startsWith('окно добычи')) return 'окно добычи не закрывается — сумки полные?';
    else if (l.result === 'бой во время сбора') return this.fightAndLoot(st);
    return null;
  },

  async grind(opts = {}) {
    const cfg = Object.assign(this.grindCfg, opts || {});
    this.grinding = true;
    const anchor = this.state(), t0 = Date.now();
    const st = this.grindStats = { kills: 0, loots: 0, rests: 0, wanders: 0, skipped: 0,
                                   anchor: { x: anchor.x, y: anchor.y }, startedAt: t0 };
    const out = r => { this.stop(); return { result: r, stats: st, ...this.state() }; };

    while (this.grinding) {
      const s = this.state();
      if (s.hp === 0) return out('персонаж погиб');
      if (cfg.maxKills && st.kills >= cfg.maxKills) return out('лимит убийств достигнут');
      if (Date.now() - t0 > cfg.maxMinutes * 60000) return out('время вышло');

      if (s.fight) {                                        // на нас напали — сначала отбиться
        const p = await this.fightAndLoot(st); if (p) return out(p); continue;
      }
      if (s.hp / s.hpMax < cfg.restBelow || s.manaPct < cfg.manaBelow) {
        await this.rest({ manaBelow: cfg.manaBelow, healKey: cfg.healKey, drinkKey: cfg.drinkKey });
        st.rests++; continue;
      }
      if (Math.hypot(s.x - anchor.x, s.y - anchor.y) > cfg.radius * 1.5) {   // ушли далеко — назад
        const m = await this.moveTo(anchor.x, anchor.y);
        if (m.result === 'застрял') return out('застрял по пути к центру');
        continue;
      }

      const tgt = await this.findTarget();
      if (!this.grinding) break;
      if (tgt) {
        if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); continue; }
        const p = await this.fightAndLoot(st); if (p) return out(p);
        continue;
      }

      st.wanders++;                                         // никого — побродить внутри радиуса
      if (st.wanders > 15 && st.kills === 0) return out('мобов поблизости не нашёл');
      const a = Math.random() * 2 * Math.PI, r = cfg.radius * (0.4 + 0.6 * Math.random());
      await this.moveTo(anchor.x + r * Math.cos(a), anchor.y + r * Math.sin(a));
    }
    return out('остановлен вручную');
  },
});
