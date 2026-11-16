/* =====================================================================
   00-core.js — база LastHavens-бота.

   Модули бота лежат в src/main/resources/bot/ и склеиваются по порядку из
   manifest.txt через ";\n" (это делает GameBridge). Здесь только общие
   функции и базовый объект window.bot; действия добавляются через
   Object.assign(bot, {...}) в 10-move / 20-fight / 30-loot.

   Экстренная остановка: bot.stop()
   ===================================================================== */

// ===== базовые функции =====
window.sleep = ms => new Promise(r => setTimeout(r, ms));
window.CANVAS = () => document.getElementById('canvas');

window.press = async (code, key, keyCode, ms = 60, target = CANVAS()) => {
  const o = { key, code, keyCode, which: keyCode, bubbles: true };
  target.dispatchEvent(new KeyboardEvent('keydown', o));
  await sleep(ms);
  target.dispatchEvent(new KeyboardEvent('keyup', o));
};

// ===== печать в чат игры =====
// Игра сообщает о поле ввода через wowTextFocus(mode): 0 — закрыто, 1/2 — открыто
// (2 — режим «без касания» на компьютере; фокус в #wow-text при этом НЕ ставится,
// поэтому document.activeElement для проверки не годится). Перехватываем один раз.
if (!window.__wowTextFocusOrig) window.__wowTextFocusOrig = window.wowTextFocus;
window.__gameEditMode = window.__gameEditMode || 0;
window.wowTextFocus = m => {
  window.__gameEditMode = m;
  return window.__wowTextFocusOrig && window.__wowTextFocusOrig(m);
};

// написать строку в чат игры и отправить (например, chat('/run print(1)'))
// Предохранители:
//  - экран входа (поле пароля) — ничего не печатаем ('not in world');
//  - чат не открылся после Enter (2 попытки) — честная ошибка ('chat did not open'),
//    а не печать «в пустоту».
window.chat = async text => {
  const field = () => document.getElementById('wow-text');
  const isPasswordScreen = () => !!field() && field().type === 'password';
  if (isPasswordScreen()) throw new Error('not in world');

  for (let attempt = 1; ; attempt++) {
    await press('Enter', 'Enter', 13);
    await sleep(250);
    if (isPasswordScreen()) throw new Error('not in world');
    if (window.__gameEditMode !== 0) break;                 // игра открыла поле чата
    if (attempt >= 2) throw new Error('chat did not open');
  }
  for (const ch of text) wowKey(ch.codePointAt(0));
  await sleep(100);
  wowKey(13);
};

window.KEYS = { W:['KeyW','w',87], A:['KeyA','a',65], D:['KeyD','d',68], S:['KeyS','s',83],
  SPACE:['Space',' ',32], TAB:['Tab','Tab',9] };
for (let n = 1; n <= 9; n++) KEYS[n] = ['Digit' + n, '' + n, 48 + n];
KEYS[0] = ['Digit0', '0', 48];
// буквы A–Z (баффы паладина на G/T/Y и т. п.); W/A/S/D уже заданы выше
for (let c = 65; c <= 90; c++) { const L = String.fromCharCode(c); if (!KEYS[L]) KEYS[L] = ['Key' + L, L.toLowerCase(), c]; }
KEYS['0'] = KEYS[0];

// ===== базовый объект бота =====
window.bot = {
  addr: null, stride: 0, saddr: null, sstride: 0, iaddr: null, istride: 0, qaddr: null, qstride: 0, daddr: null, dstride: 0, held: {}, running: false, grinding: false, lastSpellAt: {}, warnings: [],
  lastKey: {}, grindStats: null,
  // активный профиль; переживает reload_bot через window.__wowProfile (страница при этом не перезагружается)
  profile: window.__wowProfile || null,
  // ---- РОТАЦИЯ: приоритетный список слотов панели 1–9 ----
  // Готовность умений берём из маски B[13] (state().ready), а не из таймеров.
  // maxHpPct — применять умение, только если HP ниже этой доли.
  // outOfCombatOnly — только вне боя (открывающий удар).
  // waitAfter { key, ms } — не переходить к следующим умениям, пока с нажатия key
  // прошло меньше ms (чтобы удар не запустил ГКД раньше лечения).
  rotation: [
    { key: '9', maxHpPct: 0.15 },
    { key: '4', maxHpPct: 0.35 },
    { key: '7', maxHpPct: 0.35, waitAfter: { key: '4', ms: 1000 } },
    { key: '8', maxHpPct: 0.25 },
    { key: '3', outOfCombatOnly: true },
    { key: '1' },
  ],

  mem() { return new Float64Array(wasmMemory.buffer); },

  async findTable(markA, markB, timeIndex) {
    const H = this.mem(), c = [];
    for (let i = 0; i < H.length - 60; i++)
      if (H[i] === markA)
        for (let s = 1; s <= 4; s++) if (H[i + s] === markB) c.push([i, s]);
    const t0 = c.map(([i, s]) => H[i + timeIndex * s]);
    await sleep(500);
    const H2 = this.mem();
    const ok = c.filter(([i, s]) => H2[i + timeIndex * s] > 0 && H2[i + timeIndex * s] !== t0[c.indexOf([i,s])]);
    // fallback: simpler filter without t0 index issues
    const ok2 = c.filter(([i, s], k) => H2[i + timeIndex * s] > 0 && H2[i + timeIndex * s] !== t0[k]);
    const res = ok2.length ? ok2 : ok;
    if (!res.length) return null;
    return res[0];
  },

  // найти Lua-таблицы B, S, I в памяти WASM
  async find() {
    const b = await this.findTable(777001.5, 777002.5, 8);
    if (b) { this.addr = b[0]; this.stride = b[1]; console.log('НАШЁЛ B ✅', this.state()); }
    else console.warn('Не нашёл B. Lua-команды введены?');
    const s = await this.findTable(777003.5, 777004.5, 4);
    if (s) { this.saddr = s[0]; this.sstride = s[1]; console.log('НАШЁЛ S ✅'); }
    else console.warn('Не нашёл S.');
    const i = await this.findTable(777005.5, 777006.5, 4);
    if (i) { this.iaddr = i[0]; this.istride = i[1]; console.log('НАШЁЛ I ✅', this.bags()); }
    else console.warn('Не нашёл I.');
    const q = await this.findTable(777009.5, 777010.5, 2);
    if (q) { this.qaddr = q[0]; this.qstride = q[1]; console.log('НАШЁЛ Q (питомец) ✅', this.pet()); }
    else console.warn('Не нашёл Q (питомец).');
    const d = await this.findTable(777007.5, 777008.5, 2);
    if (d) { this.daddr = d[0]; this.dstride = d[1]; console.log('НАШЁЛ D (смерть) ✅', this.deathInfo()); }
    else console.warn('Не нашёл D (смерть).');
  },

  // D-таблица: смерть/призрак/труп. null — таблица не найдена (старый Lua).
  // corpseX/Y в тех же единицах, что x/y игрока (×100); 0,0 — труп в другой зоне/неизвестен.
  deathInfo() {
    if (this.daddr == null) return null;
    const H = this.mem(), g = n => H[this.daddr + n * this.dstride];
    return { dead: g(3) === 1, ghost: g(4) === 1,
             corpseX: +(g(5) * 100).toFixed(2), corpseY: +(g(6) * 100).toFixed(2),
             recoveryDelay: g(7), zone: g(8), ammo: g(9), lootOpened: g(10), xp: g(11), xpMax: g(12), feigning: g(13) === 1 };
  },

  // Q-таблица: питомец. null — таблица не найдена (старый Lua).
  // ttPlayer/ttPet — цель бьёт игрока/питомца; tBeast — цель того же типа, что питомец (зверь).
  pet() {
    if (this.qaddr == null) return null;
    const H = this.mem(), g = n => H[this.qaddr + n * this.qstride];
    return { exists: g(3) === 1, hpPct: +g(4).toFixed(2), dead: g(5) === 1, mend: g(6) === 1,
             ttPlayer: g(7) === 1, ttPet: g(8) === 1, tBeast: g(9) === 1 };
  },

  // Q[11]: идёт каст или канал игрока (нельзя жать другие клавиши, нельзя двигаться).
  // Q[12]: игрок мёртв или призрак (UnitIsDeadOrGhost). Старый Lua без них: false.
  casting() {
    if (this.qaddr == null) return false;
    return this.mem()[this.qaddr + 10 * this.qstride] === 1;
  },
  // верхом ли игрок (Q[13] = IsMounted); старый Lua без ячейки: false
  mounted() {
    if (this.qaddr == null) return false;
    return this.mem()[this.qaddr + 12 * this.qstride] === 1;
  },
  // мёртв или призрак (D, иначе Q[12] старого Lua)
  dead() {
    const d = this.deathInfo();
    if (d) return d.dead || d.ghost;
    if (this.qaddr == null) return false;
    return this.mem()[this.qaddr + 11 * this.qstride] === 1;
  },

  // игровое время (GetTime, B[9]) без округления — по нему видно, что игра обработала кадр
  gameTime() { return this.mem()[this.addr + 8 * this.stride]; },

  // подождать n кадров игры (B[9] сдвинулся n раз) — после /target, клика и т.п.
  async waitFrames(n = 2, maxWait = 700) {
    let last = this.gameTime(), ticks = 0;
    const dl = Date.now() + maxWait;
    while (Date.now() < dl && ticks < n) {
      await sleep(40);
      const t = this.gameTime();
      if (t !== last) { ticks++; last = t; }
    }
  },

  // Tab и ожидание, пока таблица B обновится (≥ 2 кадра игры): иначе читаем ещё старую цель.
  async tabTarget(maxWait = 700) {
    let last = this.gameTime(), ticks = 0;
    await this.tap('TAB');
    const dl = Date.now() + maxWait;
    while (Date.now() < dl && ticks < 2) {
      await sleep(40);
      const t = this.gameTime();
      if (t !== last) { ticks++; last = t; }
    }
    return this.state();
  },

  state() {
    const H = this.mem(), g = n => H[this.addr + n * this.stride];
    const pet = this.pet(), di = this.deathInfo();
    return {
      x: +(g(2) * 100).toFixed(2), y: +(g(3) * 100).toFixed(2), facing: +g(4).toFixed(2),
      hp: g(5), hpMax: g(6), fight: g(7) === 1, time: +g(8).toFixed(1),
      target: g(9) === 1, targetHp: g(10), enemy: g(11) === 1,
      ready: g(12), err: g(13), errTime: +g(14).toFixed(2), moDead: g(15) === 1, lootN: g(16),
      tLvl: g(17), tFlags: g(18), tDist: g(19), manaPct: +g(20).toFixed(2),
      moAlive: g(21) === 1, moLvl: g(22), kills: g(23), zone: g(24), pLvl: g(25),
      targetHpMax: g(26), targetHpPct: g(26) > 0 ? +(g(10) / g(26)).toFixed(2) : 0,
      useCount: g(27), useSlot: g(28),
      luaVer: g(29),
      pet,
      petExists: !!pet && pet.exists, petHpPct: pet ? pet.hpPct : 0, petDead: !!pet && pet.exists && pet.dead,
      ammo: di ? di.ammo : 0,
      dead: !!di && di.dead, ghost: !!di && di.ghost
    };
  },

  // S-таблица: массив записей {nameHash, spellId, slot, keyCode, mods, ready, inRange}
  spells() {
    if (this.saddr === null) return [];
    const H = this.mem(), g = n => H[this.saddr + n * this.sstride];
    const n = g(5) | 0;
    const out = [];
    let idx = 6;
    for (let i = 0; i < n; i++) {
      out.push({ nameHash: g(idx), spellId: g(idx+1), slot: g(idx+2), keyCode: g(idx+3),
        mods: g(idx+4), ready: g(idx+5), inRange: g(idx+6) });
      idx += 7;
    }
    return out;
  },
  castInfo() {
    if (this.saddr === null) return { count: 0, lastSpell: 0 };
    const H = this.mem(), g = n => H[this.saddr + n * this.sstride];
    return { count: g(2) | 0, lastSpell: g(3) | 0 };
  },

  // I-таблица: сумки/торговец/продажа
  bags() {
    if (this.iaddr == null) {
      return { free: 0, total: 0, merchantOpen: 0, toSell: 0, sold: 0, repairCost: 0, money: 0, moIsTarget: 0 };
    }
    const H = this.mem(), g = n => H[this.iaddr + n * this.istride];
    return {
      free: g(2) | 0,
      total: g(3) | 0,
      merchantOpen: g(5) | 0,
      toSell: g(6) | 0,
      sold: g(7) | 0,
      repairCost: g(8) | 0,
      money: g(9) | 0,
      moIsTarget: g(10) | 0
    };
  },

  key(k, down) {
    if (down) this.lastBotPressAt = Date.now();
    if (!!this.held[k] === down) return;
    const [code, key, keyCode] = KEYS[k];
    CANVAS().dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup',
        { key, code, keyCode, which: keyCode, bubbles: true }));
    this.held[k] = down;
  },
  async tap(k) { this.key(k, true); await sleep(60); this.key(k, false); },

  // нажать умение по хешу названия: найти запись в spells(), нажать с модификаторами
  async pressSpell(nameHash) {
    const rec = (this.spells() || []).find(r => r.nameHash === nameHash);
    if (!rec || !rec.keyCode) { this.warnings.push('spell not on bars: ' + nameHash); return false; }
    const codeFor = c => {
      if (c >= 65 && c <= 90) return 'Key' + String.fromCharCode(c);
      if (c >= 48 && c <= 57) return 'Digit' + String.fromCharCode(c);
      if (c === 189) return 'Minus';
      if (c === 187) return 'Equal';
      if (c >= 112 && c <= 123) return 'F' + (c - 111);
      return null;
    };
    const keyChar = c => (c >= 65 && c <= 90) ? String.fromCharCode(c) : (c >= 48 && c <= 57) ? String.fromCharCode(c) : (c===189?'-':(c===187?'=':''));
    const code = codeFor(rec.keyCode);
    if (!code) { this.warnings.push('unsupported key for spell: ' + nameHash); return false; }
    const key = keyChar(rec.keyCode);
    // «-» и «=»: синтетическое нажатие игра, похоже, не принимает — предупреждаем один раз
    if ((rec.keyCode === 187 || rec.keyCode === 189) && !(this.warnedKeys = this.warnedKeys || {})[nameHash]) {
      this.warnedKeys[nameHash] = true;
      this.warnings.push('клавиши «-» и «=» игра может не принимать; если умение ' + nameHash +
          ' не срабатывает, перебинди его на 1–9, 0 или букву');
    }
    const mods = rec.mods || 0;
    const shift = !!(mods & 1), ctrl = !!(mods & 2), alt = !!(mods & 4);
    const canvas = CANVAS();
    const modDown = [];
    if (shift) { canvas.dispatchEvent(new KeyboardEvent('keydown', {key:'Shift', code:'ShiftLeft', keyCode:16, shiftKey:true, bubbles:true})); modDown.push('shift'); }
    if (ctrl) { canvas.dispatchEvent(new KeyboardEvent('keydown', {key:'Control', code:'ControlLeft', keyCode:17, ctrlKey:true, bubbles:true})); modDown.push('ctrl'); }
    if (alt) { canvas.dispatchEvent(new KeyboardEvent('keydown', {key:'Alt', code:'AltLeft', keyCode:18, altKey:true, bubbles:true})); modDown.push('alt'); }
    this.lastBotPressAt = Date.now();
    canvas.dispatchEvent(new KeyboardEvent('keydown', {key, code, keyCode: rec.keyCode, which: rec.keyCode, shiftKey: shift, ctrlKey: ctrl, altKey: alt, bubbles:true}));
    await sleep(60);
    canvas.dispatchEvent(new KeyboardEvent('keyup', {key, code, keyCode: rec.keyCode, which: rec.keyCode, shiftKey: shift, ctrlKey: ctrl, altKey: alt, bubbles:true}));
    if (alt) canvas.dispatchEvent(new KeyboardEvent('keyup', {key:'Alt', code:'AltLeft', keyCode:18, bubbles:true}));
    if (ctrl) canvas.dispatchEvent(new KeyboardEvent('keyup', {key:'Control', code:'ControlLeft', keyCode:17, bubbles:true}));
    if (shift) canvas.dispatchEvent(new KeyboardEvent('keyup', {key:'Shift', code:'ShiftLeft', keyCode:16, bubbles:true}));
    this.lastSpellAt[nameHash] = Date.now();
    return true;
  },
  async hold(keys, ms) {
    keys = [].concat(keys);
    keys.forEach(k => this.key(k, true)); await sleep(ms); keys.forEach(k => this.key(k, false));
  },
  mouse(fx, fy, type = 'mousemove', button = 0) {
    const c = CANVAS(), r = c.getBoundingClientRect();
    c.dispatchEvent(new MouseEvent(type, {
      clientX: r.left + fx * r.width, clientY: r.top + fy * r.height,
      button, buttons: type === 'mousedown' ? (button === 2 ? 2 : 1) : 0, bubbles: true
    }));
  },
  async click(fx, fy, button = 0) {
    this.mouse(fx, fy, 'mousemove'); await sleep(40);
    this.mouse(fx, fy, 'mousedown', button); await sleep(60); this.mouse(fx, fy, 'mouseup', button);
  },
  dispose() { if (this.trackInt) { clearInterval(this.trackInt); this.trackInt = null; } if (this.keyLogger) { document.removeEventListener('keydown', this.keyLogger, true); this.keyLogger = null; } },
  release() { this.running = false; for (const k in this.held) this.key(k, false); },
  stop() {
    this.stopSeq = (this.stopSeq || 0) + 1;   // для долгих задач с ожиданиями (revive)
    this.hunting = false;
    this.grinding = false;
    // Задача не должна остаться в статусе 'running': по нему 60-track.js решает,
    // что действие сделал бот, и тогда ручные нажатия игрока не пишутся в БД.
    if (this.job && this.job.status === 'running') {
      this.job = { name: this.job.name, status: 'stopped', started: this.job.started, stoppedAt: Date.now(), id: this.job.id, uid: this.job.uid };
    }
    this.release();
  },
  // задать активный профиль; null — сбросить. window.__wowProfile переживает reload_bot.
  setProfile(p) {
    this.profile = p || null;
    window.__wowProfile = this.profile;
    return this.profile;
  },

  // ---- фоновый режим для Claude: start → report ----
  job: { status: 'idle' },
  // id задачи защищает от гонки: после stop() старая задача ещё дорабатывает цикл и не должна
  // затирать статус новой. Пока она не завершилась, новую не запускаем (иначе два цикла управляют
  // персонажем одновременно); если старая зависла дольше 15 с после stop — разрешаем.
  start(name, ...args) {
    if (this.job.status === 'running') return 'уже занят: ' + this.job.name;
    if (typeof this[name] !== 'function') return 'неизвестная задача: ' + name;
    if (this.jobBusy && !(this.job.stoppedAt && Date.now() - this.job.stoppedAt > 15000)) {
      return 'предыдущая задача ещё останавливается — повтори через секунду';
    }
    const id = this.jobSeq = (this.jobSeq || 0) + 1;
    const uid = 'j' + Date.now().toString(36) + id;     // идентификатор запуска для статистики (БД: bot_job)
    this.job = { name, status: 'running', started: Date.now(), id, uid };
    this.jobBusy = true;
    this.events.push({ type: 'job', phase: 'start', uid, name, at: Date.now() });
    // конец задачи → событие для статистики: статус, итог и сводка (статистика гринда/охоты)
    const finished = (status, r) => this.events.push({ type: 'job', phase: 'end', uid, name, status: (r && r.status) || status,
        result: r && r.result ? String(r.result).slice(0, 200) : null, stats: (r && r.stats) || null,
        hunt: (r && r.hunt) || null, at: Date.now() });
    // Promise.resolve().then: синхронное исключение или не-промис тоже не оставят задачу в 'running'
    Promise.resolve().then(() => this[name](...args))
        .then(r => { if (this.job.id === id) { this.job = { name, status: 'done', id, uid, ...r }; finished('done', r); } })
        .catch(e => { if (this.job.id === id) { this.job = { name, status: 'error', id, uid, error: '' + e }; finished('error', { result: '' + e }); } })
        .finally(() => { if (this.jobSeq === id) this.jobBusy = false; });
    return 'запущено: ' + name;
  },
  report() {
    const s = this.state();
    const di = this.deathInfo();
    const xp = di && di.xpMax > 0 ? Math.min(100, Math.round(di.xp / di.xpMax * 100)) : null;
    return JSON.stringify({ job: this.job, hpPct: Math.round(s.hp / s.hpMax * 100), ...s, xpPct: xp, xpLeftPct: xp === null ? null : 100 - xp, healLog: (this.healLog || []).slice(-10), grind: this.grindStats || null, route: this.routeProgress || null, hunt: this.huntStats || null, warn: (this.warnings || []).slice(-5) });
  }
};