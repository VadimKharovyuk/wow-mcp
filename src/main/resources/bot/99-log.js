// 99-log.js — журнал бота: ошибки и важные события боя по профилю уходят в БД (таблица bot_log, хранится 10 дней).
// Запись = событие {type:'log', profile, level, cat, msg, ...данные}; EventCollector кладёт его в БД.
// Категории: press_fail, heal, fight, approach, loot, buff, death, job, vendor, error.
Object.assign(bot, {
  // level: debug | info | warn | error; cat — категория; msg — короткий текст; data — доп. поля (без zone/x/y/at/type)
  log(level, cat, msg, data) {
    try {
      if (this.events.length > 5000) return;                    // сборщик не читает события — не копим бесконечно
      let s = {};
      try { s = this.addr === null ? {} : this.state(); } catch (e) { /* не в мире — без координат */ }
      this.events.push({ ...(data || {}), type: 'log', profile: this.eventProfile(), level, cat,
        msg: String(msg).slice(0, 300), zone: s.zone, x: s.x, y: s.y, jobUid: this.job && this.job.uid, at: Date.now() });
    } catch (e) { /* журнал не должен ломать бота */ }
  },

  // Предупреждения (this.warnings.push) → журнал: категория определяется по тексту
  logWarn(m) {
    m = String(m);
    if (m.startsWith('лечение (')) return;                      // пишется отдельно в heal, с подробностями
    const code = (m.match(/клавиша (\d+)/) || [])[1];
    const cc = this.combatCfg && this.combatCfg();
    const isBuffKey = code && cc && cc.buffs && cc.buffs.some(b => b.key.charCodeAt(0) === +code);
    if (m.startsWith('нажатие не сработало')) return this.log('warn', isBuffKey ? 'buff' : 'press_fail', m);
    if (m.startsWith('spell not on bars') || m.startsWith('unsupported key') || m.startsWith('клавиши')) return this.log('warn', 'press_fail', m);
    if (m.startsWith('лут:')) return this.log('warn', 'loot', m);
    if (m.startsWith('персонаж погиб')) return this.log('error', 'death', m);
    if (m.startsWith('воскрешён') || m.startsWith('смерть вне') || m.startsWith('у трупа')) return this.log('info', 'death', m);
    if (m.startsWith('переключился') || m.startsWith('притворился') || m.startsWith('ловушка') || m.startsWith('отход')) return this.log('info', 'fight', m);
    if (m.startsWith('боеприпасов')) return this.log('warn', 'fight', m);
    if (m.startsWith('воскрешаю питомца') || m.startsWith('воскрешение питомца') || m.startsWith('нет «Воскрешения')) return this.log('info', 'fight', m);
    if (m.startsWith('застрял') || m.startsWith('hunt:')) return this.log('warn', 'job', m);
    return this.log('info', 'job', m);
  }
});

// push в warnings дублирует сообщение в журнал (один раз, даже если модули загрузили повторно)
if (!bot.warnings.__logged) {
  const origPush = bot.warnings.push.bind(bot.warnings);
  bot.warnings.push = (...a) => { a.forEach(m => bot.logWarn(m)); return origPush(...a); };
  Object.defineProperty(bot.warnings, '__logged', { value: true, enumerable: false });
}

// Обёртки: бой, лут, подход, баффы, продажа — один раз на вызов, без прежней логики
(function () {
  const pct = (hp, max) => max > 0 ? Math.round(hp / max * 100) : null;

  const _fight = bot.fight;
  bot.fight = async function (o) {
    const s0 = this.state(), t0 = Date.now();
    let r;
    try { r = await _fight.call(this, o); }
    catch (e) { this.log('error', 'fight', 'исключение в бою: ' + e); throw e; }
    try {
      const s1 = this.state(), res = r && r.result ? String(r.result) : '?';
      const bad = res !== 'бой окончен' && res !== 'притворился мёртвым';
      this.log(bad ? 'warn' : 'info', 'fight', res, { durMs: Date.now() - t0, hp0: pct(s0.hp, s0.hpMax), hp1: pct(s1.hp, s1.hpMax),
        mana0: Math.round((s0.manaPct || 0) * 100), mana1: Math.round((s1.manaPct || 0) * 100), tLvl: s0.tLvl, mobs: this.fightMobs, opener: this.fightOpener || null, attackers: this.fightMaxAttackers || null });
    } catch (e) { /* журнал не мешает */ }
    return r;
  };

  // отдых: почему вошли (HP/мана/питомец), мана и HP до и после, какие клавиши нажимались (видно, пил ли), сколько длился
  const _rest = bot.rest;
  bot.rest = async function (o) {
    const s0 = this.state(), t0 = Date.now(), keys = [];
    const origTap = this.tap;
    this.tap = async function (k, ...a) { keys.push(String(k)); return origTap.call(this, k, ...a); };
    let r;
    try { r = await _rest.call(this, o); }
    catch (e) { this.log('error', 'rest', 'исключение в отдыхе: ' + e); throw e; }
    finally { this.tap = origTap; }
    try {
      const s1 = this.state(), res = r && r.result ? String(r.result) : '?';
      const mb = o && o.manaBelow != null ? o.manaBelow : null;
      const why = [];
      if (s0.hp / s0.hpMax < 0.7) why.push('HP');
      if (mb != null && s0.manaPct < mb) why.push('мана');
      if (!why.length) why.push('питомец/другое');
      this.log(res === 'отдохнул' ? 'info' : 'warn', 'rest', res, { why: why.join('+'), durMs: Date.now() - t0,
        hp0: pct(s0.hp, s0.hpMax), hp1: pct(s1.hp, s1.hpMax),
        mana0: Math.round((s0.manaPct || 0) * 100), mana1: Math.round((s1.manaPct || 0) * 100),
        manaBelow: mb != null ? Math.round(mb * 100) : null, keys });
    } catch (e) { /* журнал не мешает */ }
    return r;
  };

  const _loot = bot.loot;
  bot.loot = async function (o) {
    this.lootInfo = null;
    const r = await _loot.call(this, o);
    const res = r && r.result ? String(r.result) : '?';
    this.log(res === 'добыча собрана' ? 'info' : 'warn', 'loot', res, this.lootInfo || undefined);
    return r;
  };

  const _approach = bot.approach;
  bot.approach = async function (ms) {
    const far = !!this.farName, t0 = Date.now();
    const ok = await _approach.call(this, ms);
    // далёкие цели пишет сам approach (с причиной); здесь — только обычные неудачи
    if (!far && !ok && !this.approachSteered) this.log('warn', 'approach', 'цель: не дошёл' + (this.approachWhy ? ' (' + this.approachWhy + ')' : ''),
        { far: false, ok: false, why: this.approachWhy || null, durMs: Date.now() - t0 });
    return ok;
  };

  const _buffs = bot.ensureBuffs;
  bot.ensureBuffs = async function (cc) {
    const before = Object.assign({}, this.buffAt || {});
    const r = await _buffs.call(this, cc);
    const done = Object.keys(this.buffAt || {}).filter(k => this.buffAt[k] !== before[k]);
    if (done.length) this.log('info', 'buff', 'нажаты баффы: ' + done.join(','));
    return r;
  };

  const _sellAt = bot.sellAt;
  bot.sellAt = async function (v) {
    const r = await _sellAt.call(this, v);
    const res = r && r.result ? String(r.result) : '?';
    this.log(res === 'продано' ? 'info' : 'warn', 'vendor', (v && v.name ? v.name + ': ' : '') + res,
        { sold: r && r.sold, moneyDelta: r && r.moneyDelta, freeAfter: r && r.freeAfter });
    return r;
  };
})();

// Учёт времени гринда по этапам (бой, лут, отдых, бег, поиск, подход...): «исключительное» время — вложенный этап
// забирает время у внешнего. Накапливается в grindStats.phases (мс); в статусе — доли в процентах.
(function () {
  const PH = { fight: 'бой', loot: 'лут', rest: 'отдых', moveTo: 'бег', findTarget: 'поиск', approach: 'подход',
               ensureBuffs: 'баффы', ensurePet: 'питомец', sellAt: 'торговец', revive: 'смерть' };
  let stack = [], last = 0, owner = null;
  const charge = (st, name, now) => { const p = st.phases = st.phases || {}; p[name] = (p[name] || 0) + (now - last); last = now; };
  for (const fn of Object.keys(PH)) {
    const orig = bot[fn];
    if (typeof orig !== 'function') continue;
    bot[fn] = async function (...a) {
      const st = this.grinding && this.grindStats && !this.grindStats.endedAt ? this.grindStats : null;
      if (!st) return orig.apply(this, a);
      if (owner !== st) { owner = st; stack = []; last = Date.now(); }
      const now = Date.now();
      if (stack.length) charge(st, stack[stack.length - 1], now); else last = now;
      stack.push(PH[fn]);
      try { return await orig.apply(this, a); }
      finally {
        const t = Date.now();
        if (owner === st) { charge(st, stack.pop(), t); }
      }
    };
  }
})();

// Перезагрузка бота обрывает работавшую задачу без записи конца — пишем это в журнал
try {
  const prev = window.__prevBot;
  if (prev && prev !== bot && prev.job && prev.job.status === 'running') {
    bot.log('warn', 'job', 'прервано перезагрузкой бота: ' + prev.job.name);
    bot.events.push({ type: 'job', phase: 'end', uid: prev.job.uid, name: prev.job.name, status: 'stopped', result: 'прервано перезагрузкой бота', stats: prev.grindStats || null, at: Date.now() });
  }
  window.__prevBot = null;
} catch (e) { /* не мешаем загрузке */ }

// Ошибки Lua-модулей combat/plates: счётчики растут в таблицах игры — рост пишем в журнал (категория lua, уровень error)
(function () {
  const _drain = bot.drainEvents;
  let seen = { combat: 0, plates: 0 };
  bot.drainEvents = function () {
    try {
      const e = this.luaErrors();
      for (const k of ['combat', 'plates']) {
        if (e[k] != null && e[k] > seen[k]) {
          this.log('error', 'lua', 'ошибка Lua в модуле ' + k + ': всего ' + e[k] + ' (+' + (e[k] - seen[k]) + ')', { module: k, total: e[k] });
          seen[k] = e[k];
        }
      }
    } catch (err) { /* журнал не мешает */ }
    return _drain.call(this);
  };
})();
