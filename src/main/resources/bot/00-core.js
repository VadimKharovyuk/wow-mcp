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

// написать строку в чат игры и отправить (например, chat('/run print(1)'))
window.chat = async text => {
  await press('Enter', 'Enter', 13);
  await sleep(200);
  for (const ch of text) wowKey(ch.codePointAt(0));
  await sleep(100);
  wowKey(13);
};

window.KEYS = { W:['KeyW','w',87], A:['KeyA','a',65], D:['KeyD','d',68], S:['KeyS','s',83],
                SPACE:['Space',' ',32], TAB:['Tab','Tab',9] };
for (let n = 1; n <= 9; n++) KEYS[n] = ['Digit' + n, '' + n, 48 + n];
KEYS[0] = ['Digit0', '0', 48];
KEYS['0'] = KEYS[0];

// ===== базовый объект бота =====
window.bot = {
  addr: null, stride: 0, held: {}, running: false, grinding: false,
  lastKey: {}, grindStats: null,
  // ---- РОТАЦИЯ: приоритетный список слотов панели 1–9 ----
  // Готовность умений берём из маски B[13] (state().ready), а не из таймеров.
  // maxHpPct — применять умение, только если HP ниже этой доли.
  // outOfCombatOnly — только вне боя (открывающий удар).
  // waitAfter { key, ms } — не переходить к следующим умениям, пока с нажатия key
  // прошло меньше ms (чтобы удар не запустил ГКД раньше лечения).
  rotation: [
    { key: '2', maxHpPct: 0.35 },                                    // стан при низком HP
    { key: '4', maxHpPct: 0.35, waitAfter: { key: '2', ms: 1000 } }, // лечение через 1 с после стана
    { key: '3', outOfCombatOnly: true },                             // агро издалека, начало боя
    { key: '1' },                                                    // основной удар
  ],

  mem() { return new Float64Array(wasmMemory.buffer); },

  // найти Lua-таблицу B в памяти WASM по меткам 777001.5 / 777002.5
  async find() {
    const H = this.mem(), c = [];
    for (let i = 0; i < H.length - 60; i++)
      if (H[i] === 777001.5)
        for (let s = 1; s <= 4; s++) if (H[i + s] === 777002.5) c.push([i, s]);
    const t0 = c.map(([i, s]) => H[i + 8 * s]);
    await sleep(500);
    const H2 = this.mem();
    // настоящая таблица — та, где поле GetTime() меняется
    const ok = c.filter(([i, s], k) => H2[i + 8 * s] > 0 && H2[i + 8 * s] !== t0[k]);
    if (!ok.length) return console.warn('Не нашёл. Lua-команды введены?');
    [this.addr, this.stride] = ok[0];
    console.log('НАШЁЛ ✅', this.state());
  },

  state() {
    const H = this.mem(), g = n => H[this.addr + n * this.stride];
    return {
      x: +(g(2) * 100).toFixed(2), y: +(g(3) * 100).toFixed(2), facing: +g(4).toFixed(2),
      hp: g(5), hpMax: g(6), fight: g(7) === 1, time: +g(8).toFixed(1),
      target: g(9) === 1, targetHp: g(10), enemy: g(11) === 1,
      ready: g(12), err: g(13), errTime: +g(14).toFixed(2), moDead: g(15) === 1, lootN: g(16),
      tLvl: g(17), tFlags: g(18), tDist: g(19), manaPct: +g(20).toFixed(2),
      moAlive: g(21) === 1, moLvl: g(22), kills: g(23), zone: g(24), pLvl: g(25),
       luaVer: g(29)    };
  },

  key(k, down) {
    if (!!this.held[k] === down) return;
    const [code, key, keyCode] = KEYS[k];
    CANVAS().dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup',
      { key, code, keyCode, which: keyCode, bubbles: true }));
    this.held[k] = down;
  },
  async tap(k) { this.key(k, true); await sleep(60); this.key(k, false); },
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
  dispose() { if (this.trackInt) { clearInterval(this.trackInt); this.trackInt = null; } },
  release() { this.running = false; for (const k in this.held) this.key(k, false); },
  stop()    { this.grinding = false; this.release(); },

  // ---- фоновый режим для Claude: start → report ----
  job: { status: 'idle' },
  start(name, ...args) {
    if (this.job.status === 'running') return 'уже занят: ' + this.job.name;
    this.job = { name, status: 'running', started: Date.now() };
    this[name](...args).then(r => { this.job = { name, status: 'done', ...r }; })
                       .catch(e => { this.job = { name, status: 'error', error: '' + e }; });
    return 'запущено: ' + name;
  },
  report() {
    const s = this.state();
    return JSON.stringify({ job: this.job, hpPct: Math.round(s.hp / s.hpMax * 100), ...s, grind: this.grindStats || null });
  }
};
