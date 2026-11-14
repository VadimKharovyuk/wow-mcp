// 60-track.js — постоянная запись событий (убийства, путь, нажатия, применения) в память страницы.
Object.assign(bot, {
  events: [], lastKills: null, lastTLvl: null, recording: false, recSession: null, lastPathPt: null,
  // профиль переживает reload_bot: страница не перезагружается, window.__wowProfile остаётся
  profile: window.__wowProfile || null, keyLogger: null, fightStartedAt: null, lastHumanKey: null,
  lastBotPressAt: 0, lastUseCount: null, lastCastCount: null, lastCastAddr: null,
  lastTrackError: null, lastTrackErrorAt: 0,

  // «Это действие бота»: идёт гринд, запущена задача или бот только что нажал клавишу.
  // job.status === 'running' учитывается только пока задача реально идёт: stop()
  // переводит задачу в 'stopped', иначе все ручные нажатия игрока считались бы «ботом».
  botActedWithin(ms = 3000) {
    const now = Date.now();
    return this.grinding === true
        || (this.job && this.job.status === 'running')
        || (now - (this.lastBotPressAt || 0)) < ms;
  },

  // Профиль, под которым пишутся события. Без профиля события раньше просто терялись;
  // теперь они идут в 'default' — иначе spell_cast никогда не наполняется.
  eventProfile() {
    return this.profile || 'default';
  },

  trackTick() {
    if (this.addr === null) return;
    const s = this.state();
    if (s.target && s.enemy && s.targetHp > 0) this.lastTLvl = s.tLvl;
    if (this.lastKills === null || s.kills < this.lastKills) this.lastKills = s.kills;   // сброс после /reload
    while (s.kills > this.lastKills) {
      this.lastKills++;
      this.events.push({ type: 'kill', zone: s.zone, x: s.x, y: s.y,
        mobLevel: this.lastTLvl === null ? null : s.pLvl + this.lastTLvl, playerLevel: s.pLvl,
        source: this.botActedWithin(3000) ? 'bot' : 'player', at: Date.now() });
    }
    if (this.recording && (s.x || s.y)) {
      const p = this.lastPathPt;
      if (!p || p.zone !== s.zone || Math.hypot(s.x - p.x, s.y - p.y) >= 0.3) {
        this.lastPathPt = { zone: s.zone, x: s.x, y: s.y };
        this.events.push({ type: 'path', zone: s.zone, x: s.x, y: s.y, session: this.recSession, at: Date.now() });
      }
    }

    // Применения заклинаний: Lua-счётчик S[3] растёт на каждое UNIT_SPELLCAST_SUCCEEDED
    // (любым способом: клавиша, кнопка на панели, макрос). Таблица S могла быть найдена
    // заново — тогда счётчик начинаем с текущего значения, а не с нуля.
    if (this.saddr !== null && this.lastCastAddr !== this.saddr) {
      this.lastCastAddr = this.saddr;
      this.lastCastCount = null;
    }
    const ci = this.castInfo ? this.castInfo() : { count: 0, lastSpell: 0 };
    if (this.lastCastCount === null || ci.count < this.lastCastCount) this.lastCastCount = ci.count;
    if (ci.count > this.lastCastCount) {
      const now = Date.now();
      const isBot = this.botActedWithin(3000);
      if (!isBot) {
        const readySpells = (this.spells() || []).filter(r => r.ready === 1).map(r => r.nameHash);
        if (s.fight && !this.fightStartedAt) this.fightStartedAt = now;
        if (!s.fight) this.fightStartedAt = null;
        this.events.push({ type: 'cast', profile: this.eventProfile(), spellId: ci.lastSpell,
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

    // key_press больше не наполняем (дублирует spell_cast и путает обучение).
    // Нажатия через UseAction больше не пишутся в события.
    if (this.events.length > 5000) this.events.splice(0, this.events.length - 5000);
  },
  startTracker() {
    if (this.trackInt) return;
    this.trackInt = setInterval(() => {
      try {
        this.trackTick();
      } catch (e) {
        // Не глотаем ошибку молча: иначе поломка трекера выглядит как «событий нет».
        this.lastTrackError = '' + (e && e.message ? e.message : e);
        if (Date.now() - (this.lastTrackErrorAt || 0) > 10000) {
          this.lastTrackErrorAt = Date.now();
          console.warn('trackTick:', this.lastTrackError);
        }
      }
    }, 150);
  },
  drainEvents() { const e = this.events; this.events = []; return JSON.stringify(e); },

  // keydown-слушатель отключён: нажатия ловятся через UseAction в trackTick.
  installKeyLogger() { /* disabled */ },
});
bot.startTracker();
