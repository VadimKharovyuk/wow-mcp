// 60-track.js — постоянная запись событий (убийства + путь) в память страницы.
Object.assign(bot, {
  events: [], lastKills: null, lastTLvl: null, recording: false, recSession: null, lastPathPt: null,

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
});
bot.startTracker();
