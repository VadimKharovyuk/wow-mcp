/* =====================================================================
   LastHavens bot — сниппет для DevTools (Sources → Snippets → Cmd+Enter)

   ПОРЯДОК ЗАПУСКА
   1. Открыть игру, войти персонажем.
   2. В игре ввести 3 Lua-команды (лучше сохранить их макросами /macro):

   /run B={777001.5,777002.5,0,0,0,0,0,0,0,0,0,0} SetMapToCurrentZone()
   /run function U() local x,y=GetPlayerMapPosition("player") B[3]=x B[4]=y B[5]=GetPlayerFacing() B[6]=UnitHealth("player") B[7]=UnitHealthMax("player") B[8]=UnitAffectingCombat("player") and 1 or 0 B[9]=GetTime() end
   /run function V() B[10]=UnitExists("target") and 1 or 0 B[11]=UnitHealth("target") B[12]=UnitCanAttack("player","target") and 1 or 0 end CreateFrame("Frame"):SetScript("OnUpdate",function() U() V() end)

   3. Запустить этот сниппет (Cmd+Enter) — в конце он сам вызовет bot.find().

   После /reload в игре: повторить Lua-команды и выполнить  await bot.find()
   После обновления страницы: всё заново (шаги 2–3).
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

// ===== бот =====
window.bot = {
  addr: null, stride: 0, held: {}, running: false,

  // ---- РОТАЦИЯ: заполни под своего персонажа ----
  // key — клавиша на панели, cd — перезарядка (мс), maxHpPct — только если HP ниже этой доли
  rotation: [
    { key: '3', cd: 30000, maxHpPct: 0.4 },  // пример: лечение/защита, если HP < 40%
    { key: '2', cd: 8000 },                  // пример: удар с перезарядкой
    { key: '1', cd: 0 },                     // основной удар
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
      target: g(9) === 1, targetHp: g(10), enemy: g(11) === 1
    };
  },

  key(k, down) {
    if (!!this.held[k] === down) return;
    const [code, key, keyCode] = KEYS[k];
    CANVAS().dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup',
      { key, code, keyCode, which: keyCode, bubbles: true }));
    this.held[k] = down;
  },
  async tap(k) { this.key(k, true); await sleep(60); this.key(k, false); },
  stop() { this.running = false; for (const k in this.held) this.key(k, false); },

  // бег к точке (координаты зоны 0–100); останавливается при бое
  // обход препятствий: прыжок, затем шаг назад + поворот + пробежка в сторону
  async moveTo(tx, ty, { tol = 0.3, timeout = 90000 } = {}) {
    const norm = a => Math.atan2(Math.sin(a), Math.cos(a));
    const hold = async (keys, ms) => {
      keys.forEach(k => this.key(k, true)); await sleep(ms); keys.forEach(k => this.key(k, false));
    };
    this.running = true;
    let stuck = 0, side = 1, last = this.state(), lastMove = Date.now();
    const t0 = Date.now();

    while (this.running) {
      const s = this.state();
      const done = r => { this.stop(); return { result: r, stuckTries: stuck, ...s }; };
      if (s.x === 0 && s.y === 0) return done('нет координат (закрой карту мира)');
      if (s.fight) return done('бой');
      const dx = tx - s.x, dy = ty - s.y;
      if (Math.hypot(dx, dy) < tol) return done('дошёл');
      if (Date.now() - t0 > timeout) return done('таймаут');

      // 0 = север, угол растёт против часовой (как GetPlayerFacing)
      const err = norm(Math.atan2(-dx, -dy) - s.facing);
      this.key('A', err > 0.08);
      this.key('D', err < -0.08);
      this.key('W', Math.abs(err) < 0.6);

      if (Math.hypot(s.x - last.x, s.y - last.y) > 0.05) { last = s; lastMove = Date.now(); }
      else if (this.held.W && Date.now() - lastMove > 1500) {
        // упёрлись
        if (++stuck > 6) return done('застрял');
        this.key('W', false); this.key('A', false); this.key('D', false);
        if (stuck === 1) {
          await this.tap('SPACE');                       // сначала просто прыжок (низкий забор, камень)
        } else {
          await hold(['S'], 600);                        // шаг назад
          await hold([side > 0 ? 'A' : 'D'], 400 + 150 * stuck);   // поворот ~70–180°
          await hold(['W'], 1500 + 300 * stuck);         // пробежать в сторону
          if (stuck % 2 === 1) side = -side;             // чередовать лево/право
        }
        last = this.state(); lastMove = Date.now();
      }
      await sleep(100);
    }
    return { result: 'остановлен вручную', ...this.state() };
  },

  async fight({ fleeAt = 0.2, timeout = 90000 } = {}) {
    this.running = true;
    const last = {}, t0 = Date.now();
    while (this.running) {
      const s = this.state(), hpPct = s.hp / s.hpMax;
      if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
      if (hpPct < fleeAt) { this.stop(); return { result: 'мало HP — нужен выбор', ...s }; }
      if (Date.now() - t0 > timeout) { this.stop(); return { result: 'бой слишком долгий', ...s }; }
      if (!s.target || !s.enemy || s.targetHp === 0) { await this.tap('TAB'); await sleep(300); continue; }
      const r = this.rotation.find(r =>
        Date.now() - (last[r.key] || 0) > r.cd && (!r.maxHpPct || hpPct < r.maxHpPct));
      if (r) { await this.tap(r.key); last[r.key] = Date.now(); }
      await sleep(1600); // глобальная перезарядка
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
  },

  // дойти до точки, по пути отбиваясь
  async go(x, y) {
    for (let i = 0; i < 10; i++) {
      const r = await this.moveTo(x, y);
      if (r.result !== 'бой') return r;
      const f = await this.fight();
      if (f.result !== 'бой окончен') return f;
    }
    return { result: 'слишком много боёв по пути', ...this.state() };
  },

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
    return JSON.stringify({ job: this.job, hpPct: Math.round(s.hp / s.hpMax * 100), ...s });
  }
};
