// 20-fight.js — бой по реальной готовности умений и отдых.
Object.assign(bot, {
  async fight({ fleeAt = 0.2, timeout = 90000 } = {}) {
    this.running = true;
    const t0 = Date.now();
    let lastPress = 0, handledErr = 0, turns = 0;
    while (this.running) {
      const s = this.state(), hpPct = s.hp / s.hpMax;
      if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
      if (hpPct < fleeAt) { this.stop(); return { result: 'мало HP — нужен выбор', ...s }; }
      if (Date.now() - t0 > timeout) { this.stop(); return { result: 'бой слишком долгий', ...s }; }

      if (!s.target || !s.enemy || s.targetHp === 0) { await this.tap('TAB'); await sleep(300); continue; }

      // реакция на свежую ошибку, случившуюся после нашего нажатия
      if (s.errTime > lastPress && s.errTime > handledErr) {
        handledErr = s.errTime;
        if (s.err === 1 && turns < 4) { await this.hold('A', 450); turns++; continue; }  // ~90° поворот
        if (s.err === 2) { await this.hold('W', 500); continue; }                       // подойти ближе
      }

      const isReady = k => (s.ready >> (+k - 1)) & 1;
      const r = this.rotation.find(r => isReady(r.key) && (!r.maxHpPct || hpPct < r.maxHpPct));
      if (r) { await this.tap(r.key); lastPress = s.time; turns = 0; }
      await sleep(250);
    }
    return { result: 'остановлен вручную', ...this.state() };
  },

  async rest({ until = 0.8, foodKey = null, timeout = 60000 } = {}) {
    const t0 = Date.now();
    if (foodKey) await this.tap(foodKey);
    while (Date.now() - t0 < timeout) {
      const s = this.state();
      if (s.fight) return { result: 'бой во время отдыха', ...s };
      if (s.hp / s.hpMax >= until) return { result: 'отдохнул', ...s };
      await sleep(1000);
    }
    return { result: 'отдых: таймаут', ...this.state() };
  }
});
