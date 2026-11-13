// 20-fight.js — бой по реальной готовности умений и отдых.

// Сколько не двигаться после нажатия 4: чтобы не сбить чтение лечащего заклинания.
const HEAL_CAST_LOCK_MS = 3000;

Object.assign(bot, {
  async fight({ fleeAt = 0.2, timeout = 90000 } = {}) {
    this.running = true;
    const t0 = Date.now();
    let lastPress = 0, handledErr = 0, turns = 0;
    while (this.running) {
      const s = this.state(), hpPct = s.hp / s.hpMax;
      if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
      if (hpPct < fleeAt) { this.release(); return { result: 'мало HP — нужен выбор', ...s }; }
      if (Date.now() - t0 > timeout) { this.release(); return { result: 'бой слишком долгий', ...s }; }

      if (!s.target || !s.enemy || s.targetHp === 0) { await this.tap('TAB'); await sleep(300); continue; }

      const isReady = k => (s.ready >> (+k - 1)) & 1;
      const now = Date.now();
      this.lastKey = this.lastKey || {};

      // реакция на свежую ошибку, случившуюся после нашего нажатия
      if (s.errTime > lastPress && s.errTime > handledErr) {
        handledErr = s.errTime;
        if (s.err === 1 && turns < 4) { await this.hold('A', 450); turns++; continue; }  // ~90° поворот
        // не двигаемся сразу после 4 — иначе собьём чтение лечения
        if (s.err === 2 && now - (this.lastKey['4'] || 0) > HEAL_CAST_LOCK_MS) {
          await this.hold('W', 500); continue;                                           // подойти ближе
        }
      }

      let pick = null;
      for (const r of this.rotation) {
        if (!isReady(r.key)) continue;
        if (r.maxHpPct && hpPct >= r.maxHpPct) continue;
        if (r.outOfCombatOnly && s.fight) continue;
        if (r.waitAfter && now - (this.lastKey[r.waitAfter.key] || 0) < r.waitAfter.ms) { pick = 'wait'; break; }
        pick = r; break;
      }
      if (pick && pick !== 'wait') {
        await this.tap(pick.key);
        this.lastKey[pick.key] = Date.now();
        lastPress = s.time; turns = 0;
      }
      await sleep(250);
    }
    return { result: 'остановлен вручную', ...this.state() };
  },

  async rest({ hpUntil = 0.8, manaUntil = 0.9, manaBelow = 0.3,
                healKey = '4', drinkKey = '0', timeout = 60000 } = {}) {
    const t0 = Date.now();
    const isReady = (s, k) => (s.ready >> (+k - 1)) & 1;
    let s = this.state();
    const bail = r => ({ result: r, ...this.state() });

    // 1) питьё, если мало маны
    if (drinkKey && s.manaPct < manaBelow) {
      await this.tap(drinkKey);
      while (Date.now() - t0 < timeout) {
        await sleep(1000); s = this.state();
        if (s.fight) return bail('бой во время отдыха');
        if (s.manaPct >= manaUntil) break;
      }
    }
    // 2) лечение заклинанием (до 3 раз)
    for (let i = 0; i < 3 && healKey; i++) {
      s = this.state();
      if (s.fight) return bail('бой во время отдыха');
      if (s.hp / s.hpMax >= hpUntil || s.manaPct < 0.15) break;
      if (!isReady(s, healKey)) { await sleep(500); continue; }
      await this.tap(healKey);
      await sleep(3000);                       // время чтения заклинания
    }
    // 3) добрать естественным отдыхом
    while (Date.now() - t0 < timeout) {
      s = this.state();
      if (s.fight) return bail('бой во время отдыха');
      if (s.hp / s.hpMax >= hpUntil) return bail('отдохнул');
      await sleep(1000);
    }
    return bail('отдых: таймаут');
  }
});
