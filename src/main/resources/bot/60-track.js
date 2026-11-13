// 60-track.js — постоянная запись событий (убийства + путь) в память страницы.
Object.assign(bot, {
  events: [], lastKills: null, lastTLvl: null, recording: false, recSession: null, lastPathPt: null,
  profile: null, keyLogger: null, fightStartedAt: null, lastHumanKey: null,

  trackTick() {
    if (this.addr === null) return;
    const s = this.state();
    if (s.target && s.enemy && s.targetHp > 0) this.lastTLvl = s.tLvl;
    if (this.lastKills === null || s.kills < this.lastKills) this.lastKills = s.kills;   // сброс после /reload
    while (s.kills > this.lastKills) {
      this.lastKills++;
      this.events.push({ type: 'kill', zone: s.zone, x: s.x, y: s.y,
        mobLevel: this.lastTLvl === null ? null : s.pLvl + this.lastTLvl, playerLevel: s.pLvl,
        source: this.grinding || this.job.status === 'running' ? 'bot' : 'player', at: Date.now() });
    }
    if (this.recording && (s.x || s.y)) {
      const p = this.lastPathPt;
      if (!p || p.zone !== s.zone || Math.hypot(s.x - p.x, s.y - p.y) >= 0.3) {
        this.lastPathPt = { zone: s.zone, x: s.x, y: s.y };
        this.events.push({ type: 'path', zone: s.zone, x: s.x, y: s.y, session: this.recSession, at: Date.now() });
      }
    }
    if (this.events.length > 5000) this.events.splice(0, this.events.length - 5000);
  },
  startTracker() { if (!this.trackInt) this.trackInt = setInterval(() => { try { this.trackTick(); } catch (e) {} }, 500); },
  drainEvents() { const e = this.events; this.events = []; return JSON.stringify(e); },

  installKeyLogger() {
    if (this.keyLogger) return;
    this.keyLogger = e => {
      if (!e.isTrusted || e.repeat) return;
      const m = /^Digit([0-9])$/.exec(e.code); if (!m) return;
      const active = document.activeElement;
      if (active && active.id === 'wow-text') return;
      if (this.addr === null || !this.profile) return;
      const s = this.state(), now = Date.now();
      if (s.fight && !this.fightStartedAt) this.fightStartedAt = now;
      if (!s.fight) this.fightStartedAt = null;
      this.events.push({ type: 'key', profile: this.profile, key: m[1],
        hp: +(s.hp / s.hpMax).toFixed(2), mana: s.manaPct, ready: s.ready, fight: s.fight,
        target: s.target && s.enemy, targetHpPct: s.targetHpPct,
        sinceFightMs: this.fightStartedAt ? now - this.fightStartedAt : null,
        prevKey: this.lastHumanKey ? this.lastHumanKey.key : null,
        prevKeyMs: this.lastHumanKey ? now - this.lastHumanKey.at : null, at: now });
      this.lastHumanKey = { key: m[1], at: now };
    };
    document.addEventListener('keydown', this.keyLogger, true);
  },
});
bot.startTracker();
bot.installKeyLogger();
