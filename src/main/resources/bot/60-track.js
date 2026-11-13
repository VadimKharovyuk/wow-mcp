// 60-track.js — постоянная запись событий (убийства + путь) в память страницы.
Object.assign(bot, {
  events: [], lastKills: null, lastTLvl: null, recording: false, recSession: null, lastPathPt: null,
  profile: null, keyLogger: null, fightStartedAt: null, lastHumanKey: null,
  lastBotPressAt: 0, lastUseCount: null, lastCastCount: null,

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
    // Отслеживание нажатий умений через игру (UseAction hook): рост useCount.
    // Отслеживание применений заклинаний (через Lua-событие UNIT_SPELLCAST_SUCCEEDED).
    if (this.lastCastCount === null) this.lastCastCount = this.castInfo ? this.castInfo().count : 0;
    const ci = this.castInfo ? this.castInfo() : { count: 0, lastSpell: 0 };
    if (ci.count > this.lastCastCount) {
      const now = Date.now();
      const isBot = this.grinding || this.job.status === 'running' || (now - (this.lastBotPressAt || 0)) < 3000;
      if (!isBot && this.profile) {
        const readySpells = (this.spells() || []).filter(r => r.ready === 1).map(r => r.nameHash);
        if (s.fight && !this.fightStartedAt) this.fightStartedAt = now;
        if (!s.fight) this.fightStartedAt = null;
        this.events.push({ type: 'cast', profile: this.profile, spellId: ci.lastSpell,
          hp: +(s.hp / s.hpMax).toFixed(2), mana: s.manaPct, fight: s.fight,
          target: s.target && s.enemy, targetHpPct: s.targetHpPct,
          readySpells: readySpells,
          sinceFightMs: this.fightStartedAt ? now - this.fightStartedAt : null,
          prevSpell: this.lastCastSpell || null,
          prevSpellMs: this.lastCastSpell ? now - (this.lastCastAt || now) : null, at: now });
      }
      this.lastCastSpell = ci.lastSpell;
      this.lastCastAt = now;
      this.lastCastCount = ci.count;
    }

    if (this.lastUseCount === null) this.lastUseCount = s.useCount;
    if (s.useCount > this.lastUseCount) {
      const slot = s.useSlot;
      const now = Date.now();
      const isBot = (now - (this.lastBotPressAt || 0)) < 400;
      if (!isBot && slot >= 1 && slot <= 9 && this.profile) {
        if (s.fight && !this.fightStartedAt) this.fightStartedAt = now;
        if (!s.fight) this.fightStartedAt = null;
        this.events.push({ type: 'key', profile: this.profile, key: String(slot),
          hp: +(s.hp / s.hpMax).toFixed(2), mana: s.manaPct, ready: s.ready, fight: s.fight,
          target: s.target && s.enemy, targetHpPct: s.targetHpPct,
          sinceFightMs: this.fightStartedAt ? now - this.fightStartedAt : null,
          prevKey: this.lastHumanKey ? this.lastHumanKey.key : null,
          prevKeyMs: this.lastHumanKey ? now - this.lastHumanKey.at : null, at: now });
        this.lastHumanKey = { key: String(slot), at: now };
      }
      this.lastUseCount = s.useCount;
    }
    if (this.events.length > 5000) this.events.splice(0, this.events.length - 5000);
  },
  startTracker() { if (!this.trackInt) this.trackInt = setInterval(() => { try { this.trackTick(); } catch (e) {} }, 150); },
  drainEvents() { const e = this.events; this.events = []; return JSON.stringify(e); },

  // keydown-слушатель отключён: нажатия ловятся через UseAction в trackTick.
  installKeyLogger() { /* disabled */ },
});
bot.startTracker();