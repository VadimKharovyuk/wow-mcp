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
        source: this.botActedWithin(3000) ? 'bot' : 'player',
        jobUid: this.job && this.job.status === 'running' ? this.job.uid : null, at: Date.now() });
    }
    this.trackStats(s);
    if (this.recording) {
      // ручная запись (record_start): source 'manual' — «важный» путь для маршрутов и графа дорог
      if (s.x || s.y) {
        const p = this.lastPathPt;
        if (!p || p.zone !== s.zone || Math.hypot(s.x - p.x, s.y - p.y) >= 0.3) {
          this.lastPathPt = { zone: s.zone, x: s.x, y: s.y };
          this.events.push({ type: 'path', zone: s.zone, x: s.x, y: s.y, session: this.recSession, source: 'manual', at: Date.now() });
        }
      }
    } else {
      this.trackAutoPath(s);
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
  // Статистика для дашборда: открытие окна добычи (бот и игрок), смерть, снимки опыта и денег.
  trackStats(s) {
    const di = this.deathInfo();
    if (!di) return;
    const now = Date.now(), bot = this.botActedWithin(3000);
    const jobUid = this.job && this.job.status === 'running' ? this.job.uid : null;
    // LOOT_OPENED: счётчик из Lua растёт на каждое открытое окно добычи
    if (this.lastLootOpened == null || di.lootOpened < this.lastLootOpened) this.lastLootOpened = di.lootOpened;
    while (di.lootOpened > this.lastLootOpened) {
      this.lastLootOpened++;
      this.events.push({ type: 'loot', source: bot ? 'bot' : 'player', jobUid, zone: s.zone, x: s.x, y: s.y, at: now });
    }
    // смерть: переход жив → мёртв/призрак
    const dead = di.dead || di.ghost;
    if (dead && this.wasDead === false) {
      const la = this.lastAlive || {};
      this.events.push({ type: 'death', source: this.botActedWithin(10000) ? 'bot' : 'player', jobUid, zone: s.zone,
        x: s.x, y: s.y, hpBefore: la.hpPct, manaBefore: la.manaPct, at: now });
    }
    this.wasDead = dead;
    // Смерть вне задачи (бой закончился неудачей, задача уже завершилась): если бот недавно работал (3 мин),
    // сам воскрешаемся через 8 с. Смерть при ручной игре (бот давно не действовал) не трогаем.
    if (dead) {
      this.deadSince = this.deadSince || now;
      if (this.autoReviveIdle !== false && now - this.deadSince > 8000 && now - (this.lastReviveTry || 0) > 30000
          && !(this.job && this.job.status === 'running') && !this.jobBusy && this.botActedWithin(180000)) {
        this.lastReviveTry = now;
        this.warnings.push('смерть вне задачи — запускаю воскрешение');
        if (this.warnings.length > 20) this.warnings.shift();
        this.start('revive');
      }
    } else {
      this.deadSince = 0;
    }
    // снимок опыта и денег: при изменении, не чаще раза в 5 с (опыт в час, доход в час)
    const money = this.bags().money || 0, snap = this.lastStat;
    if (!snap || ((snap.xp !== di.xp || snap.money !== money || snap.level !== s.pLvl) && now - snap.at >= 5000)) {
      this.lastStat = { xp: di.xp, money, level: s.pLvl, at: now };
      this.events.push({ type: 'stat', level: s.pLvl, xp: di.xp, xpMax: di.xpMax, money, zone: s.zone, at: now });
    }
  },

  // Постоянная запись пути: всегда, пока персонаж в мире. source: 'player' — ведёт игрок (бот не действовал
  // последние 5 с), 'bot' — движение бота. Новый трек (session): пауза > 30 с, смена зоны, смерть, скачок > 5 ед.
  // (телепорт) или смена источника. Точка: смещение ≥ 0.3 ед. или (раз в ~1 с и смещение ≥ 0.05).
  trackAutoPath(s) {
    if (this.dead && this.dead()) { this.autoSession = null; this.lastAutoPt = null; return; }
    if (!s.x && !s.y) return;
    const now = Date.now(), src = this.botActedWithin(5000) ? 'bot' : 'player', p = this.lastAutoPt;
    if (!this.autoSession || !p || p.zone !== s.zone || p.src !== src || now - p.at > 30000
        || Math.hypot(s.x - p.x, s.y - p.y) > 5) {
      this.autoSession = 'auto-' + now.toString(36) + '-' + src[0];
      this.lastAutoPt = null;
    }
    const q = this.lastAutoPt, moved = q ? Math.hypot(s.x - q.x, s.y - q.y) : Infinity;
    if (!q || moved >= 0.3 || (now - q.at >= 1000 && moved >= 0.05)) {
      this.lastAutoPt = { zone: s.zone, x: s.x, y: s.y, at: now, src };
      this.events.push({ type: 'path', zone: s.zone, x: s.x, y: s.y, session: this.autoSession, source: src, at: now });
    }
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
