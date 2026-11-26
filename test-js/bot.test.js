// Тесты логики бота без игры: node --test test-js/   (или ./test-js.sh)
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadBot } = require('./harness');

// массивы из vm-контекста имеют другой прототип — для deepEqual приводим к обычным
const plain = x => JSON.parse(JSON.stringify(x));

const fresh = () => { const b = loadBot(); b.logs = []; b.log = (...a) => b.logs.push(a); return b; };

// ---------- мост игра ↔ бот: чего не хватает ----------
test('dataMissing: всё на месте', () => {
  const b = fresh();
  Object.assign(b, { saddr: 1, iaddr: 1, qaddr: 1, daddr: 1 });
  b.state = () => ({ hp: 100, pLvl: 39 });
  assert.deepEqual(plain(b.dataMissing()), []);
});

test('dataMissing: нет таблицы сумок и пуста цепочка Lua', () => {
  const b = fresh();
  Object.assign(b, { saddr: 1, iaddr: null, qaddr: 1, daddr: 1 });
  b.state = () => ({ hp: 100, pLvl: 0 });
  assert.deepEqual(plain(b.dataMissing()), ['I', 'B[18..27]']);
});

test('dataMissing: мёртвый персонаж (hp 0) не считается поломкой цепочки', () => {
  const b = fresh();
  Object.assign(b, { saddr: 1, iaddr: 1, qaddr: 1, daddr: 1 });
  b.state = () => ({ hp: 0, pLvl: 0 });
  assert.deepEqual(plain(b.dataMissing()), []);
});

// ---------- перебежки верхом ----------
function travelSetup({ x = 10, y = 10, fight = false, mountedAfterMove = true, moveThrows = false } = {}) {
  const b = fresh(); const calls = [];
  let mounted = false;
  b.state = () => ({ x, y, fight });
  b.mountUp = async () => { calls.push('mountUp'); mounted = true; return true; };
  b.moveTo = async () => { calls.push('moveTo'); if (moveThrows) throw new Error('x'); return { result: 'дошёл' }; };
  b.mounted = () => mounted && mountedAfterMove;
  b.dismount = async () => { calls.push('dismount'); mounted = false; };
  return { b, calls };
}

test('travelTo: далеко и вне боя — садится, бежит, слезает', async () => {
  const { b, calls } = travelSetup();
  await b.travelTo(15, 10, {}, {});
  assert.deepEqual(calls, ['mountUp', 'moveTo', 'dismount']);
});

test('travelTo: близко — пешком', async () => {
  const { b, calls } = travelSetup();
  await b.travelTo(10.3, 10, {}, {});
  assert.deepEqual(calls, ['moveTo']);
});

test('travelTo: в бою не садится', async () => {
  const { b, calls } = travelSetup({ fight: true });
  await b.travelTo(30, 10, {}, {});
  assert.deepEqual(calls, ['moveTo']);
});

test('travelTo: mountTravel=false выключает коня', async () => {
  const { b, calls } = travelSetup();
  await b.travelTo(30, 10, {}, { mountTravel: false });
  assert.deepEqual(calls, ['moveTo']);
});

test('travelTo: слезает, даже если moveTo упал', async () => {
  const { b, calls } = travelSetup({ moveThrows: true });
  await assert.rejects(() => b.travelTo(30, 10, {}, {}));
  assert.deepEqual(calls, ['mountUp', 'moveTo', 'dismount']);
});

// ---------- отход не дошагивает от трупа ----------
test('retreat: прекращается, когда цель умерла', async () => {
  const b = fresh(); const keys = [];
  b.running = true;
  b.key = (k, down) => keys.push([k, down]);
  let n = 0;
  b.state = () => ({ target: true, targetHp: ++n > 2 ? 0 : 500 });
  const t0 = Date.now();
  await b.retreat(700);
  assert.ok(Date.now() - t0 < 400, 'должен выйти быстро, а не ждать 700 мс');
  assert.deepEqual(keys[keys.length - 1], ['S', false]);
});

// ---------- поворот к цели ----------
test('aimBy: цель по центру — не поворачиваем', async () => {
  const b = fresh(); let turned = 0;
  b.state = () => ({ facing: 1 });
  b.turnTo = async () => { turned++; };
  assert.equal(await b.aimBy(0.5), null);
  assert.equal(turned, 0);
});

test('aimBy: цель правее — поворот вправо (facing уменьшается); возвращает ожидаемое и фактическое', async () => {
  const b = fresh(); let facing = 1;
  b.state = () => ({ facing });
  b.turnTo = async (a) => { facing = a; };
  const r = await b.aimBy(0.75);
  assert.equal(r.expected, -0.4);
  assert.equal(r.actual, -0.4);
});

test('farRunSteered: уточняет поворот, пока цель не по центру (не больше 2 раз)', async () => {
  const b = fresh(); b.grinding = true;
  const scans = [{ fx: 0.9, fy: 0.5 }, { fx: 0.7, fy: 0.5 }, { fx: 0.62, fy: 0.5 }, { fx: 0.52, fy: 0.5 }];
  b.scanOnScreen = async () => scans.shift() || null;
  let aims = 0; b.aimBy = async () => { aims++; return { expected: 0, actual: 0 }; };
  b._approach = async () => true;                         // сразу «дошёл»
  b.state = () => ({ x: 0, y: 0, fight: false });
  const ok = await b.farRunSteered(null, null, 5000);
  assert.equal(ok, true);
  assert.equal(b.approachAim.refines, 2);                 // 0.7 → поправили, 0.62 → поправили, потолок 2
  assert.equal(aims, 3);                                  // 1 грубый + 2 уточнения
});

test('farRunSteered: цель уже по центру — уточнений нет', async () => {
  const b = fresh(); b.grinding = true;
  const scans = [{ fx: 0.5, fy: 0.5 }, { fx: 0.51, fy: 0.5 }];
  b.scanOnScreen = async () => scans.shift() || null;
  b.aimBy = async () => null;
  b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false });
  await b.farRunSteered(null, null, 5000);
  assert.equal(b.approachAim.refines, 0);
});

// ---------- выбор способа подхода ----------
function approachSetup({ tDist = 1, fight = false, steer = true } = {}) {
  const b = fresh(); const calls = [];
  b.grinding = true; b.grindStats = {}; b.grindCfg = {};
  b.steerSupported = () => steer;
  b.state = () => ({ tDist, fight });
  b.farRunSteered = async () => { calls.push('steered'); return true; };
  b._approach = async () => { calls.push('straight'); return true; };
  b.key = () => {};
  return { b, calls };
}

test('approach: обычная цель, не вплотную — с подруливанием', async () => {
  const { b, calls } = approachSetup({ tDist: 1 });
  await b.approach(6000);
  assert.deepEqual(calls, ['steered']);
});

test('approach: цель вплотную (tDist&2) — сразу прямо', async () => {
  const { b, calls } = approachSetup({ tDist: 3 });
  await b.approach(6000);
  assert.deepEqual(calls, ['straight']);
});

test('approach: нет проверки «курсор над целью» — прямо', async () => {
  const { b, calls } = approachSetup({ steer: false });
  await b.approach(6000);
  assert.deepEqual(calls, ['straight']);
});

test('approach: цель не на экране — запасной бег прямо', async () => {
  const { b, calls } = approachSetup();
  b.farRunSteered = async function () { calls.push('steered'); this.approachWhy = 'цель не на экране'; return false; };
  await b.approach(6000);
  assert.deepEqual(calls, ['steered', 'straight']);
});

// ---------- поиск цели на экране: без слепого поворота ----------
test('farRunSteered (turn:false): не нашли обычным сканом — плотный скан, без поворота', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  let turned = 0; const modes = [];
  b.turnTo = async () => { turned++; };
  b.scanOnScreen = async (o) => { modes.push(o.dense ? 'dense' : o.narrow ? 'narrow' : 'normal'); return o.dense ? { fx: 0.5, fy: 0.5 } : null; };
  b.aimBy = async () => null; b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000, { turn: false }), true);
  assert.equal(turned, 0);
  assert.deepEqual(modes.slice(0, 2), ['normal', 'dense']);
  assert.equal(b.grindStats.scanDense, 1);
});

test('farRunSteered (далёкая цель): обычный скан не нашёл — плотный на месте, без поворота', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  let turned = 0; let n = 0;
  b.turnTo = async () => { turned++; };
  b.scanOnScreen = async () => (++n === 2 ? { fx: 0.5, fy: 0.5 } : null);   // 1 — обычный (мимо), 2 — плотный (нашёл)
  b.aimBy = async () => null; b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000), true);
  assert.equal(turned, 0);
  assert.equal(b.approachStage, 'плотный');
});

test('farRunSteered (далёкая цель): полный осмотр — находит после второго поворота на 90°', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  const turns = []; let n = 0;
  b.turnTo = async (a) => { turns.push(a); };
  b.scanOnScreen = async () => (++n === 4 ? { fx: 0.5, fy: 0.5 } : null);  // 1 обычный, 2 плотный, 3 после поворота 1, 4 после поворота 2
  b.aimBy = async () => null; b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000), true);
  assert.equal(turns.length, 2);
  assert.ok(Math.abs(turns[0] - Math.PI / 2) < 1e-9);
  assert.equal(b.approachStage, 'поворот 2');
  assert.equal(b.approachTurns, 2);
});

test('farRunSteered (далёкая цель): не нашли за полный круг — три поворота и «цель не на экране»', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  let turned = 0;
  b.turnTo = async () => { turned++; };
  b.scanOnScreen = async () => null;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000), false);
  assert.equal(turned, 3);
  assert.equal(b.approachWhy, 'цель не на экране');
  assert.equal(b.grindStats.scanMiss, 1);
});

test('farRunSteered: не нашли нигде — «цель не на экране», учтено в статистике', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  b.scanOnScreen = async () => null; b.turnTo = async () => {};
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000, { turn: false }), false);
  assert.equal(b.approachWhy, 'цель не на экране');
  assert.equal(b.grindStats.scanMiss, 1);
});

test('approach: цель не найдена на экране — короткие куски бега с повторным сканом, не 10 с вслепую', async () => {
  const { b } = approachSetup();
  b.farRunSteered = async function () { this.approachWhy = 'цель не на экране'; return false; };
  let runs = 0, aims = 0, dense = 0;
  b._approach = async (ms) => { runs++; assert.equal(ms, 1000); return false; };
  b.scanOnScreen = async (o) => { if (o.dense) dense++; return { fx: 0.6, fy: 0.5 }; };
  b.aimBy = async () => { aims++; return null; };
  const ok = await b.approach(6000);
  assert.equal(ok, false);
  assert.equal(runs, 3);                    // не больше трёх кусков
  assert.equal(dense, 3);
  assert.equal(aims, 3);
  assert.equal(b.approachWhy, 'нет сближения');
});

test('approach: фоллбэк дошёл на втором куске — останавливается', async () => {
  const { b } = approachSetup();
  b.farRunSteered = async function () { this.approachWhy = 'цель не на экране'; return false; };
  let runs = 0;
  b._approach = async () => ++runs === 2;
  b.scanOnScreen = async () => null; b.aimBy = async () => null;
  assert.equal(await b.approach(6000), true);
  assert.equal(runs, 2);
});

// ---------- лут ----------
function lootSetup({ moved = false, tDist = 3, gained = false } = {}) {
  const b = fresh(); const clicks = []; const bagsFree = { v: 20 };
  b.running = true; b.job = { status: 'running' }; b.events = [];
  b.steerSupported = () => true; b.overTarget = () => true;
  b.closeInOnCorpse = async () => {};
  b.mouse = (fx, fy, type) => { if (type === 'mousedown') clicks.push([fx, fy]); };
  let x = 0;
  b.state = () => ({ x: moved ? (x += 0.05) : 0, y: 0, moDead: true, target: true, targetHp: 0, lootN: 0, tDist, fight: false, zone: 1 });
  b.bags = () => ({ free: bagsFree.v, money: 0 });
  b.deathInfo = () => ({ lootOpened: 5 });
  if (gained) setTimeout(() => { bagsFree.v = 19; }, 100);        // добыча упала в сумку после клика
  return { b, clicks };
}

test('loot: пустой труп вплотную, персонаж не двигался — один клик, без повтора', async () => {
  const { b, clicks } = lootSetup();
  const r = await b.loot({ timeout: 8000 });
  assert.equal(r.result, 'пустой труп или не открылся');
  assert.equal(clicks.length, 1);
}, { timeout: 15000 });

test('loot: добыча собрана — запоминаем точку клика как подсказку', async () => {
  const { b, clicks } = lootSetup({ gained: true });
  const r = await b.loot({ timeout: 8000 });
  assert.equal(r.result, 'добыча собрана');
  assert.ok(b.lootHint && typeof b.lootHint.fx === 'number');
  assert.equal(clicks.length, 1);
}, { timeout: 15000 });

test('loot: подсказка проверяется первой', async () => {
  const { b } = lootSetup({ gained: true });
  b.lootHint = { fx: 0.33, fy: 0.61 };
  const moves = []; const origMouse = b.mouse;
  b.mouse = (fx, fy, type) => { if (type === 'mousemove') moves.push([fx, fy]); return origMouse(fx, fy, type); };
  await b.loot({ timeout: 8000 });
  assert.deepEqual(moves[0], [0.33, 0.61]);
}, { timeout: 15000 });

// ---------- приоритет умений ----------
test('activeRotation: экстренные (с условием по HP) выше, затем priority, затем остальное без дублей', () => {
  const b = fresh();
  b.profile = 'paladin';
  b.rotation = [
    { spell: 1672233375, maxHpPct: 0.45 },   // экстренное
    { spell: 1568831975, learned: true },    // Экзорцизм
    { spell: 2008405423, learned: true },    // Щит мстителя (есть в priority — не дублируется)
    { spell: 1363607377, learned: true },    // Молот праведника (в priority)
    { spell: 2037478445, learned: true },    // Правосудие света (в priority)
  ];
  const order = b.activeRotation().map(r => r.spell);
  assert.deepEqual(order, [1672233375, 1363607377, 2037478445, 2008405423, 1191036009, 1568831975]);
});

test('combatCfg паладина: лишние умения (Очищение, Аура воздаяния, Камень возвращения) в rotationSkip', () => {
  const b = fresh(); b.profile = 'paladin';
  const cc = b.combatCfg();
  for (const h of [2105405526, 1290206130, 242961069, 1433786557, 904078709]) assert.ok(cc.rotationSkip.includes(h), String(h));
  assert.deepEqual(plain(cc.priority), [1363607377, 2037478445, { spell: 2008405423, minAttackers: 2 }, { spell: 1191036009, minAttackers: 3 }]);
  assert.equal(cc.rotationSkip.includes(1191036009), false);   // Освящение управляется правилом priority, не пропускается
});

test('activeRotation: у профиля без priority — как есть', () => {
  const b = fresh(); b.profile = 'нет-такого';
  b.rotation = [{ spell: 1 }, { spell: 2 }];
  assert.deepEqual(plain(b.activeRotation()), [{ spell: 1 }, { spell: 2 }]);
});

// ---------- число врагов, бьющих игрока ----------
test('attackers: читает A[4] из памяти; нет таблицы — null', () => {
  const b = fresh();
  assert.equal(b.attackers(), null);
  const H = new Float64Array(20); H[5 + 3 * 2] = 3;          // aaddr=5, stride=2, индекс 3 → A[4]
  b.mem = () => H; b.aaddr = 5; b.astride = 2;
  assert.equal(b.attackers(), 3);
});

test('attackersOk: без условия — да; неизвестное число врагов — нет; порог «больше двух»', () => {
  const b = fresh();
  assert.equal(b.attackersOk({ spell: 1 }), true);
  assert.equal(b.attackersOk({ spell: 1, minAttackers: 2 }), false);          // таблицы нет — не жмём
  const H = new Float64Array(20); b.mem = () => H; b.aaddr = 0; b.astride = 1;
  H[3] = 1; assert.equal(b.attackersOk({ minAttackers: 2 }), false);          // один — мало
  H[3] = 2; assert.equal(b.attackersOk({ minAttackers: 2 }), true);           // двое — можно
});

test('priority: правило с minAttackers сохраняется объектом и не дублируется из ротации', () => {
  const b = fresh(); b.profile = 'paladin';
  b.rotation = [{ spell: 2008405423, learned: true }, { spell: 1568831975, learned: true }];
  const rot = plain(b.activeRotation());
  const shield = rot.filter(r => r.spell === 2008405423);
  assert.equal(shield.length, 1);
  assert.equal(shield[0].minAttackers, 2);
});

// ---------- полосы здоровья врагов (lua/plates.lua) ----------
function plateMem(slots, { count = slots.length, hasTarget = 1 } = {}) {
  // таблица P: [1..2] метки, [3] время, [4] число полос, [5] есть цель, затем слоты по 5 ячеек с P[6]; stride 2
  const stride = 2, H = new Float64Array(200);
  const set = (n, v) => { H[10 + (n - 1) * stride] = v; };       // paddr = 10
  set(1, 777013.5); set(2, 777014.5); set(3, 1); set(4, count); set(5, hasTarget);
  slots.forEach((e, i) => { const b = 6 + i * 5; set(b, e.fx); set(b + 1, e.fy); set(b + 2, (e.target ? 1 : 0) + 2 * e.re); set(b + 3, e.lvl); set(b + 4, e.nh); });
  return { H, stride };
}

test('plates: нет таблицы — null', () => {
  assert.equal(fresh().plates(), null);
});

test('plates: разбирает слоты (цель, реакция, уровень, хеш имени)', () => {
  const b = fresh(); const { H, stride } = plateMem([
    { fx: 0.667, fy: 0.45, target: true, re: 2, lvl: 70, nh: 123 },
    { fx: 0.285, fy: 0.5, target: false, re: 3, lvl: 69, nh: 456 },
  ]);
  b.mem = () => H; b.paddr = 10; b.pstride = stride;
  const p = plain(b.plates());
  assert.equal(p.length, 2);
  assert.deepEqual(p[0], { fx: 0.667, fy: 0.45, target: true, reaction: 2, level: 70, nameHash: 123 });
  assert.equal(p[1].target, false);
  assert.equal(p[1].reaction, 3);
});

test('plateAim: берёт полосу выбранной цели; нет цели или цель у края — null', () => {
  const b = fresh(); const { H, stride } = plateMem([
    { fx: 0.3, fy: 0.5, target: false, re: 3, lvl: 60, nh: 1 },
    { fx: 0.667, fy: 0.45, target: true, re: 3, lvl: 61, nh: 2 },
  ]);
  b.mem = () => H; b.paddr = 10; b.pstride = stride;
  assert.deepEqual(plain(b.plateAim()), { fx: 0.667, fy: 0.45, plate: true });
  const none = plateMem([{ fx: 0.3, fy: 0.5, target: false, re: 3, lvl: 60, nh: 1 }]);
  b.mem = () => none.H;
  assert.equal(b.plateAim(), null);
  const edge = plateMem([{ fx: 0.99, fy: 0.5, target: true, re: 3, lvl: 60, nh: 1 }]);
  b.mem = () => edge.H;
  assert.equal(b.plateAim(), null);
});

test('farRunSteered: есть полоса цели — без сканов и поворотов, сразу доворот и бег', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  b.plateChecks = 100;                                    // контрольный скан курсором уже отработан (первые 40 подходов)
  let scans = 0, turned = 0, aimedFx = null;
  b.plateAim = () => ({ fx: 0.7, fy: 0.5, plate: true });
  b.scanOnScreen = async () => { scans++; return null; };
  b.turnTo = async () => { turned++; };
  b.aimBy = async (fx) => { if (aimedFx === null) aimedFx = fx; return { expected: 0, actual: 0 }; };
  b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000, { turn: false }), true);
  assert.equal(scans, 0);
  assert.equal(turned, 0);
  assert.equal(aimedFx, 0.7);
  assert.equal(b.approachStage, 'полоса');
});

test('farRunSteered: полосы нет — ищет курсором, как раньше', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  let scans = 0;
  b.plateAim = () => null;
  b.scanOnScreen = async () => { scans++; return { fx: 0.5, fy: 0.5 }; };
  b.aimBy = async () => null; b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  assert.equal(await b.farRunSteered(null, null, 5000, { turn: false }), true);
  assert.ok(scans >= 1);
  assert.equal(b.approachStage, 'обычный');
});

test('farRunSteered: контрольный скан курсором после наведения по полосе — результат пишется в aim.check', async () => {
  const b = fresh(); b.grinding = true; b.grindStats = {};
  b.plateAim = () => ({ fx: 0.5, fy: 0.5, plate: true });
  b.scanOnScreen = async () => ({ fx: 0.53, fy: 0.5 });
  b.aimBy = async () => null; b._approach = async () => true;
  b.state = () => ({ x: 0, y: 0, fight: false, facing: 0 });
  await b.farRunSteered(null, null, 5000, { turn: false });
  assert.equal(b.approachAim.src, 'plate');
  assert.equal(b.approachAim.check, 0.53);
  assert.equal(b.plateChecks, 1);
});

test('plateAim: статистика попаданий и промахов (цель выбрана)', () => {
  const b = fresh(); b.grindStats = {};
  const withPlate = plateMem([{ fx: 0.6, fy: 0.5, target: true, re: 3, lvl: 60, nh: 1 }]);
  b.mem = () => withPlate.H; b.paddr = 10; b.pstride = withPlate.stride;
  b.plateAim();
  const noPlate = plateMem([{ fx: 0.3, fy: 0.5, target: false, re: 3, lvl: 60, nh: 1 }], { hasTarget: 1 });
  b.mem = () => noPlate.H;
  b.plateAim();
  assert.equal(b.grindStats.plateHit, 1);
  assert.equal(b.grindStats.plateMiss, 1);
});

// ---------- ошибки Lua в журнале ----------
test('luaErrors: читает счётчики A[5] и P[46]; нет таблиц — null', () => {
  const b = fresh();
  assert.deepEqual(plain(b.luaErrors()), { combat: null, plates: null });
  const H = new Float64Array(200);                         // stride 1: aaddr=0 → A[5] = H[4]; paddr=50 → P[46] = H[95]
  H[4] = 2; H[95] = 5;
  b.mem = () => H; b.aaddr = 0; b.astride = 1; b.paddr = 50; b.pstride = 1;
  assert.deepEqual(plain(b.luaErrors()), { combat: 2, plates: 5 });
});

test('drainEvents: рост счётчика ошибок Lua попадает в журнал (category lua) один раз', () => {
  const b = fresh();
  b.events = [];
  b.log = (level, cat, msg, data) => b.events.push({ level, cat, msg, ...data });
  const H = new Float64Array(200);
  b.mem = () => H; b.aaddr = 0; b.astride = 1; b.paddr = 50; b.pstride = 1;
  assert.equal(JSON.parse(b.drainEvents()).length, 0);
  H[95] = 2;                                                 // plates: 2 ошибки
  const ev = JSON.parse(b.drainEvents());                    // событие журнала появится в следующем drain
  const ev2 = JSON.parse(b.drainEvents());
  const all = ev.concat(ev2).filter(e => e.cat === 'lua');
  assert.equal(all.length, 1);
  assert.equal(all[0].level, 'error');
  assert.equal(all[0].module, 'plates');
  assert.equal(all[0].total, 2);
  assert.equal(JSON.parse(b.drainEvents()).filter(e => e.cat === 'lua').length, 0);   // без роста — не повторяем
});

// --- быстрое переключение на атакующего ---
function fastSwitchSetup({ attackers = 1, ttPlayer = false, targetHpPct = 1 } = {}) {
  const b = fresh();
  const calls = { switched: 0 };
  b.state = () => ({ hp: 100, hpMax: 100, fight: true, target: true, enemy: true, targetHp: 500, targetHpPct, attackers, tDist: 1, ready: 0, manaPct: 1 });
  b.dead = () => false;
  b.pet = () => ({ exists: false, ttPlayer, ttPet: false });
  b.switchToAttacker = async () => { calls.switched++; b.lastSwitchTabs = 2; b.running = false; return true; };
  b.release = () => {};
  return { b, calls };
}

test('fight: нас бьёт другой моб, цель нетронута — сразу переключается, без ожидания 5 с', async () => {
  const { b, calls } = fastSwitchSetup();
  await b.fight({ timeout: 2000 });
  assert.equal(calls.switched, 1);
  const rec = b.logs.find(l => l[2] && String(l[2]).startsWith('быстрое переключение'));
  assert.ok(rec, 'запись в журнал');
  assert.equal(rec[3].attackers, 1);
  assert.equal(rec[3].found, true);
});

test('fight: цель сама бьёт игрока — быстрого переключения нет', async () => {
  const { b, calls } = fastSwitchSetup({ ttPlayer: true });
  setTimeout(() => { b.running = false; }, 300);
  await b.fight({ timeout: 2000 }).catch(() => {});
  assert.equal(calls.switched, 0);
});

test('fight: счётчик врагов неизвестен (null) — быстрого переключения нет', async () => {
  const { b, calls } = fastSwitchSetup({ attackers: null });
  setTimeout(() => { b.running = false; }, 300);
  await b.fight({ timeout: 2000 }).catch(() => {});
  assert.equal(calls.switched, 0);
});

// --- память мест упора и обход ---
test('noteStuck: повтор в течение 20 с — один случай; позже — второй; надёжна при n>=2', () => {
  const b = fresh();
  const s = { zone: 215, x: 20, y: 36, facing: 1 };
  b.noteStuck(s, 'тест'); b.noteStuck(s, 'тест');
  assert.equal(b.stuckList().length, 1);
  assert.equal(b.stuckList()[0].n, 1);
  b.stuckList()[0].at -= 30000;
  b.noteStuck({ ...s, x: 20.02 }, 'тест');
  assert.equal(b.stuckList()[0].n, 2);
});

test('stuckAhead: только надёжная точка впереди по курсу, в той же зоне', () => {
  const b = fresh();
  const L = b.stuckList();
  // facing 0 = на север: dy<0 в координатах карты (у — вниз)
  L.push({ zone: 1, x: 50, y: 49.9, n: 2, at: 0 });     // 0.1 впереди
  L.push({ zone: 1, x: 50, y: 50.1, n: 5, at: 0 });     // сзади
  L.push({ zone: 2, x: 50, y: 49.95, n: 5, at: 0 });    // другая зона
  L.push({ zone: 1, x: 49.95, y: 49.95, n: 1, at: 0 }); // ненадёжная
  const a = b.stuckAhead({ zone: 1, x: 50, y: 50, facing: 0 });
  assert.ok(a);
  assert.equal(a.p.y, 49.9);
  const back = b.stuckAhead({ zone: 1, x: 50, y: 50, facing: Math.PI });           // развернулись: теперь впереди та, что была сзади
  assert.equal(back.p.y, 50.1);
});

test('avoidStuck: уходит в сторону от точки, не больше 2 раз', async () => {
  const b = fresh();
  b.stuckList().push({ zone: 1, x: 50, y: 49.9, n: 3, at: 0 });
  let facing = 0, x = 50;
  const keys = [];
  b.key = (k, on) => { keys.push(k + (on ? '+' : '-')); if (on && k === 'D') facing -= 0.3; if (on && k === 'A') facing += 0.3; };
  b.state = () => { if (keys.includes('W+')) x += 0.05; return { zone: 1, x, y: 50, facing, fight: false }; };
  const ctx = { n: 0 };
  const done = await b.avoidStuck({ zone: 1, x: 50, y: 50, facing: 0 }, ctx);
  assert.equal(done, true);
  assert.equal(ctx.n, 1);
  assert.ok(keys.some(k => k === 'A+' || k === 'D+'));
  ctx.n = 2;
  assert.equal(await b.avoidStuck({ zone: 1, x: 50, y: 50, facing: 0 }, ctx), false);
});

test('avoidStuck: нет точек — ничего не делает', async () => {
  const b = fresh();
  assert.equal(await b.avoidStuck({ zone: 1, x: 50, y: 50, facing: 0 }, { n: 0 }), false);
});

// --- «нет прогресса»: недостижимая цель ---
function noProgSetup(over = {}) {
  const b = fresh();
  b.grindCfg = { noProgressMs: 300 };
  let t = 0;
  b.state = () => ({ hp: 100, hpMax: 100, fight: false, target: true, enemy: true, targetHp: 500, targetHpMax: 500, targetHpPct: 1,
    attackers: 0, tDist: 1, tLvl: 60, ready: 0, manaPct: 1, x: 10, y: 10, zone: 1, ...over, ...(over.dyn ? over.dyn(t++) : {}) });
  b.dead = () => false;
  b.pet = () => ({ exists: false, ttPlayer: false, ttPet: false });
  b.release = () => { b.running = false; };
  b.pressSpell = async () => true;
  b.tap = async () => {};
  b.key = () => {};
  return b;
}

test('fight: HP цели не меняется, нас не бьют — «цель недостижима» (боя даже не было)', async () => {
  const b = noProgSetup();
  const r = await b.fight({ timeout: 5000 });
  assert.equal(r.result, 'цель недостижима');
});

test('fight: HP цели меняется — нет отказа', async () => {
  const b = noProgSetup({ dyn: t => ({ targetHp: 500 - t }) });
  setTimeout(() => { b.running = false; }, 700);
  const r = await b.fight({ timeout: 5000 });
  assert.notEqual(r.result, 'цель недостижима');
});

test('fight: нас бьют (attackers>=1) — нет отказа', async () => {
  const b = noProgSetup({ attackers: 1, fight: true });
  b.switchToAttacker = async () => false;
  setTimeout(() => { b.running = false; }, 700);
  const r = await b.fight({ timeout: 5000 });
  assert.notEqual(r.result, 'цель недостижима');
});

test('banTarget/targetProblem: бан по месту и уровню; другое место и истёкший бан — не бан', () => {
  const b = fresh();
  b.grindCfg = { minLvlDiff: -10, maxLvlDiff: 10 };
  b.banTarget({ x: 10, y: 10, tLvl: 60 });
  const s = { target: true, targetHp: 1, enemy: true, tFlags: 0, tLvl: 60, x: 10.05, y: 10 };
  assert.equal(b.targetProblem(s), 'недостижима');
  assert.notEqual(b.targetProblem({ ...s, x: 30 }), 'недостижима');
  assert.notEqual(b.targetProblem({ ...s, tLvl: 59 }), 'недостижима');
  b.unreachable[0].until = Date.now() - 1;
  assert.notEqual(b.targetProblem(s), 'недостижима');
});

test('fightAndLoot: «цель недостижима» — бан, гринд не останавливается', async () => {
  const b = fresh();
  b.__win.chat = async () => {};
  b.grinding = true;
  b.grindCfg = {};
  b.dead = () => false;
  b.fight = async () => ({ result: 'цель недостижима', waitedMs: 8000, x: 10, y: 10, tLvl: 60, hp: 100 });
  const st = { kills: 0 };
  const r = await b.fightAndLoot(st);
  assert.equal(r, null);
  assert.equal(st.unreachable, 1);
  assert.equal(st.kills, 0);
  assert.equal(b.unreachable.length, 1);
});

// --- мягкий таймаут боя ---
test('fight: таймаут при наличии прогресса — бой продолжается; без прогресса или за пределом — «бой слишком долгий»', async () => {
  // прогресс есть (нас бьют): после timeout бой идёт, пока не вышли за hardTimeout
  const b = noProgSetup({ attackers: 1, fight: true });
  b.switchToAttacker = async () => false;
  const r = await b.fight({ timeout: 200, hardTimeout: 700 });
  assert.equal(r.result, 'бой слишком долгий');
  assert.ok(b.logs.some(l => String(l[2]).startsWith('долгий бой')), 'запись «продолжаю»');
  // без прогресса (нас не бьют, HP цели стоит): сработает «цель недостижима» раньше, а при отключённом ей — таймаут
  const b2 = noProgSetup({ fight: true });
  b2.grindCfg = { noProgressMs: 60000, activeWindowMs: 100 };
  const t0 = Date.now();
  const r2 = await b2.fight({ timeout: 300, hardTimeout: 5000 });
  assert.equal(r2.result, 'бой слишком долгий');
  assert.ok(Date.now() - t0 < 2500);
});

// --- сближение с целью, бьющей издалека ---
function closeSetup(ranges) {
  const b = noProgSetup({ fight: true, attackers: 1 });
  b.combatCfg = () => ({ closeInSpell: 111 });
  b.switchToAttacker = async () => false;
  let i = 0;
  b.spellInRange = () => ranges[Math.min(i++, ranges.length - 1)];
  const held = [];
  b.hold = async (k, ms) => { held.push(k); };
  b.lastHealCastAt = () => 0;
  return { b, held };
}

test('fight: цель вне дальности ближнего умения — подбегает, потом пишет «подошёл»', async () => {
  const { b, held } = closeSetup([0, 0, 1]);
  setTimeout(() => { b.running = false; }, 400);
  await b.fight({ timeout: 5000 });
  assert.ok(held.filter(k => k === 'W').length >= 2);
  assert.ok(b.logs.some(l => String(l[2]).startsWith('сближение: подошёл')));
});

test('fight: цель в дальности — не бежит; профиль без closeInSpell — не бежит', async () => {
  const { b, held } = closeSetup([1]);
  setTimeout(() => { b.running = false; }, 300);
  await b.fight({ timeout: 5000 });
  assert.equal(held.filter(k => k === 'W').length, 0);
  const c = closeSetup([0]);
  c.b.combatCfg = () => ({});
  setTimeout(() => { c.b.running = false; }, 300);
  await c.b.fight({ timeout: 5000 });
  assert.equal(c.held.filter(k => k === 'W').length, 0);
});

test('combatCfg паладина: Святая клятва (H) в списке баффов', () => {
  const b = fresh();
  b.profile = 'paladin';
  const h = b.combatCfg().buffs.find(x => x.key === 'H');
  assert.ok(h && h.everyMs > 0);
});

// --- обновление баффа в бою (Святая клятва) ---
function buffFightSetup({ buffAt = 0, ready = 1 } = {}) {
  const b = noProgSetup({ fight: true, attackers: 1 });
  b.combatCfg = () => ({ buffs: [{ key: 'H', name: 'Святая клятва', everyMs: 60000, inFight: true, spell: 777 }] });
  b.spells = () => [{ nameHash: 777, ready, inRange: 1 }];
  b.switchToAttacker = async () => false;
  b.lastHealCastAt = () => 0;
  b.buffAt = { H: buffAt };
  const pressed = [];
  b.pressSpell = async h => { pressed.push(h); return true; };
  return { b, pressed };
}

test('fight: интервал баффа с inFight вышел — обновляется в бою', async () => {
  const { b, pressed } = buffFightSetup({ buffAt: Date.now() - 61000 });
  setTimeout(() => { b.running = false; }, 300);
  await b.fight({ timeout: 5000 });
  assert.ok(pressed.includes(777));
  assert.ok(b.buffAt.H > Date.now() - 2000);
  assert.ok(b.logs.some(l => String(l[2]).startsWith('в бою обновлён бафф')));
});

test('fight: интервал не вышел или умение не готово — бафф в бою не жмётся', async () => {
  const a = buffFightSetup({ buffAt: Date.now() - 1000 });
  setTimeout(() => { a.b.running = false; }, 300);
  await a.b.fight({ timeout: 5000 });
  assert.equal(a.pressed.includes(777), false);
  const c = buffFightSetup({ buffAt: Date.now() - 61000, ready: 0 });
  setTimeout(() => { c.b.running = false; }, 300);
  await c.b.fight({ timeout: 5000 });
  assert.equal(c.pressed.includes(777), false);
});

test('combatCfg паладина: Печать повиновения (G) обновляется и в бою, не жмётся из ротации', () => {
  const b = fresh();
  b.profile = 'paladin';
  const cc = b.combatCfg(), g = cc.buffs.find(x => x.key === 'G');
  assert.equal(g.spell, 1453980521);
  assert.equal(g.inFight, true);
  assert.equal(g.everyMs, 25 * 60000);
  assert.ok(cc.rotationSkip.includes(1453980521));
});

test('ensureBuffs: умение на перезарядке — таймер баффа не сдвигается, готово — жмётся', async () => {
  for (const [ready, expectPress] of [[0, false], [1, true]]) {
    const b = fresh();
    b.grinding = true;
    b.state = () => ({ fight: false, dead: false, ghost: false, manaPct: 1 });
    b.casting = () => false;
    b.spells = () => [{ nameHash: 777, ready }];
    b.waitFor = async () => true;
    const pressed = [];
    b.tap = async k => { pressed.push(k); };
    b.buffAt = { G: 0 };
    await b.ensureBuffs({ buffs: [{ key: 'G', everyMs: 25 * 60000, spell: 777 }] });
    assert.equal(pressed.includes('G'), expectPress);
    assert.equal(b.buffAt.G === 0, !expectPress);
  }
});

// --- поиск атакующего с поворотом (моб сзади) ---
test('switchToAttacker: впереди никого — поворот на ~120° и повторный Tab; нашёл — остаётся', async () => {
  const b = fresh();
  b.running = true;
  const turned = [];
  let facing = 0, tabs = 0;
  b.state = () => ({ facing, target: tabs >= 6, enemy: true, targetHp: 100 });
  b.tabTarget = async () => { tabs++; return b.state(); };
  b.pet = () => ({ ttPlayer: tabs >= 6 });
  b.turnTo = async a => { turned.push(+a.toFixed(2)); facing = a; };
  const ok = await b.switchToAttacker({ turns: 2 });
  assert.equal(ok, true);
  assert.equal(b.lastSwitchTurns, 1);          // 4 Tab впереди, поворот, найден на 2-м Tab
  assert.equal(turned.length, 1);
});

test('switchToAttacker: никого нет — два поворота и возврат к прежнему курсу', async () => {
  const b = fresh();
  b.running = true;
  const turned = [];
  let facing = 1;
  b.state = () => ({ facing, target: false });
  b.tabTarget = async () => b.state();
  b.pet = () => ({ ttPlayer: false });
  b.turnTo = async a => { turned.push(+a.toFixed(2)); facing = a; };
  const ok = await b.switchToAttacker({ turns: 2 });
  assert.equal(ok, false);
  assert.equal(b.lastSwitchTurns, 2);
  assert.equal(turned.length, 3);              // два поворота + возврат
  assert.equal(turned[2], 1);                  // вернулись к исходному курсу
});

test('switchToAttacker: без turns поворотов нет (как раньше)', async () => {
  const b = fresh();
  b.running = true;
  let turns = 0;
  b.state = () => ({ facing: 0, target: false });
  b.tabTarget = async () => b.state();
  b.pet = () => ({ ttPlayer: false });
  b.turnTo = async () => { turns++; };
  assert.equal(await b.switchToAttacker(), false);
  assert.equal(turns, 0);
  assert.equal(b.lastSwitchTabs, 4);
});

// --- посадка на коня: ждём конец призыва, не идём раньше времени ---
function mountSetup({ mountAfterMs = 1500, never = false } = {}) {
  const b = fresh();
  b.combatCfg = () => ({ mountKey: 'L' });
  b.qaddr = 1;
  b.job = { status: 'running' };
  b.running = true;
  let t0 = 0; const taps = [];
  b.state = () => ({ fight: false, dead: false, ghost: false });
  b.tap = async k => { taps.push(k); if (!t0) t0 = Date.now(); };
  b.casting = () => false;                                     // флаг каста запаздывает/не виден
  b.mounted = () => !never && t0 > 0 && Date.now() - t0 >= mountAfterMs;
  return { b, taps, started: () => t0 };
}

test('mountUp: флаг каста не виден — всё равно ждёт коня и не возвращается раньше 2 с', async () => {
  const { b, taps, started } = mountSetup({ mountAfterMs: 1500 });
  const r = await b.mountUp();
  const dt = Date.now() - started();
  assert.equal(r, true);
  assert.equal(taps.length, 1);
  assert.ok(dt >= 2000, 'прошло ' + dt);
});

test('mountUp: конь не появился — клавиша жмётся один раз (повтор на коне = спешивание), затем false', async () => {
  const { b, taps } = mountSetup({ never: true });
  const r = await b.mountUp();
  assert.equal(r, false);
  assert.equal(taps.length, 1);
});

// --- диагностика: поиск атакующего и перебежка ---
test('switchToAttacker: пишет по каждому Tab запись для журнала', async () => {
  const b = fresh(); b.running = true;
  b.state = () => ({ facing: 0, target: true, enemy: true, tLvl: 70, targetHpPct: 1, targetHp: 5, tDist: 1 });
  b.tabTarget = async () => b.state();
  b.pet = () => ({ ttPlayer: false });
  await b.switchToAttacker();
  assert.equal(b.lastSwitchInfo.length, 4);
  assert.deepEqual(plain(b.lastSwitchInfo[0]), { r: 0, tg: 1, en: 1, lvl: 70, hp: 100, tp: 0, d: 1 });
});

test('fight: быстрое переключение не сработало — в журнал пишется причина (цель бьёт игрока)', async () => {
  const b = noProgSetup({ fight: true, attackers: 2 });
  b.pet = () => ({ exists: false, ttPlayer: true, ttPet: false });
  setTimeout(() => { b.running = false; }, 300);
  await b.fight({ timeout: 5000 });
  const rec = b.logs.find(l => String(l[2]).startsWith('быстрое переключение пропущено'));
  assert.ok(rec && String(rec[2]).includes('цель бьёт игрока'));
  assert.equal(rec[3].attackers, 2);
});

test('travelTo: журнал перебежки верхом (сел, результат, слез)', async () => {
  const { b } = travelSetup();
  await b.travelTo(15, 10, {}, {});
  const rec = b.logs.find(l => l[1] === 'travel');
  assert.ok(rec, 'запись travel');
  assert.equal(rec[3].mountOk, true);
  assert.equal(rec[3].result, 'дошёл');
  assert.equal(rec[3].dismounted, true);
});

test('combatCfg паладина: Благословение неприкосновенности (Y) раз в 9 минут, в том числе в бою', () => {
  const b = fresh(); b.profile = 'paladin';
  const y = b.combatCfg().buffs.find(x => x.key === 'Y');
  assert.equal(y.everyMs, 9 * 60000);
  assert.equal(y.inFight, true);
  assert.equal(y.spell, 1791702616);
});

// --- /target по имени: далёких мобов не берём ---
function byNameSetup(tDist, cfg = {}) {
  const b = fresh();
  const chats = [];
  b.__win.chat = async c => { chats.push(c); };
  b.grinding = true;
  b.grindCfg = { names: ['Червь'], minLvlDiff: -10, maxLvlDiff: 10, ...cfg };
  b.grindStats = {};
  b.waitFrames = async () => {};
  b.state = () => ({ fight: false, target: true, targetHp: 100, enemy: true, tFlags: 0, tLvl: 70, tDist, x: 1, y: 1 });
  b.goodPullTarget = () => true;
  return { b, chats };
}

test('findByName: цель дальше 28 ярдов — снимаем, имя на паузу, не берём', async () => {
  const { b, chats } = byNameSetup(0);
  const r = await b.findByName();
  assert.equal(r, null);
  assert.ok(chats.includes('/cleartarget'));
  assert.ok(b.nameCool['Червь'] > Date.now());
  assert.equal(b.grindStats.byNameTooFar, 1);
});

test('findByName: цель ближе 28 ярдов — берём; nameFarOk возвращает прежнее поведение для далёких', async () => {
  const near = byNameSetup(1);
  assert.ok(await near.b.findByName());
  const far = byNameSetup(0, { nameFarOk: true });
  const r = await far.b.findByName();
  assert.ok(r);
  assert.equal(far.b.farName, 'Червь');
});

// --- цель сзади: доворот, пока её полоса не появится ---
test('targetOffscreen: нет таблицы полос — false; цель выбрана, полосы нет — true; полоса на экране — false', () => {
  const b = fresh();
  assert.equal(b.targetOffscreen(), false);
  b.paddr = 0; b.pstride = 1;
  const H = new Float64Array(60); b.mem = () => H;
  H[4] = 1;                      // P[5] = 1: цель есть
  H[3] = 0;                      // P[4] = 0 полос
  assert.equal(b.targetOffscreen(), true);
  H[3] = 1; H[5] = 0.5; H[6] = 0.5; H[7] = 1; H[8] = 70; H[9] = 123;   // одна полоса цели по центру
  assert.equal(b.targetOffscreen(), false);
});

test('fight: цель сзади (полосы нет) — доворачивается по 90°, пока полоса не появится', async () => {
  const b = noProgSetup({ fight: true, attackers: 1, dyn: t => ({ hp: Math.max(60, 100 - 2 * t) }) });   // наше HP падает — вертеться можно
  b.pet = () => ({ exists: false, ttPlayer: true, ttPet: false });   // цель бьёт нас — быстрого переключения нет
  let calls = 0;
  b.targetOffscreen = () => ++calls < 4;                              // 1-й вход и два шага — вне экрана, затем появилась
  const turns = [];
  b.turnTo = async a => { turns.push(+a.toFixed(2)); };
  b.plateAim = () => null;
  setTimeout(() => { b.running = false; }, 2200);
  await b.fight({ timeout: 5000 });
  assert.ok(turns.length >= 2, 'повороты: ' + turns.length);
  const rec = b.logs.find(l => String(l[2]).startsWith('доворот к цели'));
  assert.ok(rec && rec[3].found === true);
});

// --- вращение только если не наносим урон и нас реально бьют; откат после неудачного поиска ---
test('fight: HP не падает (медленная потеря/ничего) — поиск атакующего без поворотов; неудача — откат 8 с', async () => {
  const b = noProgSetup({ fight: true, attackers: 1 });
  b.pet = () => ({ exists: false, ttPlayer: false, ttPet: false });
  const args = [];
  b.switchToAttacker = async o => { args.push(o && o.turns); b.lastSwitchTabs = 4; return false; };
  setTimeout(() => { b.running = false; }, 1200);
  await b.fight({ timeout: 5000 });
  assert.deepEqual(args, [0]);                                   // один поиск без поворотов; повтор — только после отката
  const rec = b.logs.find(l => String(l[2]).startsWith('быстрое переключение: атакующего не нашёл'));
  assert.ok(rec && rec[3].canTurn === false);
  assert.ok(rec[3].backoffMs > 7000 && rec[3].backoffMs <= 8000, 'откат ' + rec[3].backoffMs);
  const skip = b.logs.find(l => String(l[2]).includes('откат после неудачного поиска'));
  assert.ok(skip, 'причина пропуска — откат');
});

test('fight: наше HP падает ≥3% и цель не получает урона — поиск с поворотами', async () => {
  const b = noProgSetup({ fight: true, dyn: t => ({ attackers: t >= 12 ? 1 : 0, hp: Math.max(60, 100 - 3 * t) }) });
  b.pet = () => ({ exists: false, ttPlayer: false, ttPet: false });
  const args = [];
  b.switchToAttacker = async o => { args.push(o && o.turns); b.running = false; return false; };
  await b.fight({ timeout: 5000 });
  assert.deepEqual(args, [2]);
});

// --- Lua: глобальные имена не должны затирать друг друга (таблица P затирала функцию P() и ломала цепочку W) ---
test('Lua: имена глобальных таблиц модулей не совпадают с функциями lua-setup и друг с другом', () => {
  const fs = require('fs'), path = require('path');
  const root = path.join(__dirname, '..', 'src', 'main', 'resources');
  const setup = fs.readFileSync(path.join(root, 'lua-setup.txt'), 'utf8');
  const funcs = new Set([...setup.matchAll(/function\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(/g)].map(m => m[1]));
  const tables = [];
  for (const m of setup.matchAll(/\/run\s+([A-Za-z_][A-Za-z_0-9]*)\s*=\s*\{/g)) tables.push(['lua-setup.txt', m[1]]);
  const mods = fs.readFileSync(path.join(root, 'lua', 'manifest.txt'), 'utf8').split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));
  for (const f of mods) {
    const src = fs.readFileSync(path.join(root, 'lua', f), 'utf8');
    for (const m of src.matchAll(/^([A-Za-z_][A-Za-z_0-9]*)\s*=\s*\{/gm)) tables.push([f, m[1]]);
    for (const m of src.matchAll(/^function\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(/gm)) funcs.add(m[1]);
  }
  const seen = new Map();
  for (const [f, name] of tables) {
    assert.equal(funcs.has(name), false, `таблица ${name} (${f}) затирает функцию с тем же именем`);
    assert.equal(seen.has(name), false, `таблица ${name} объявлена дважды: ${seen.get(name)} и ${f}`);
    seen.set(name, f);
  }
  assert.ok(tables.length >= 6);
});

// --- «Взаимодействовать с целью» (interactKey): автоподход к дальней/зарытой цели или цели сзади ---
function interactSetup({ range = 0, dyn = null, over = {}, offscreen = false } = {}) {
  const b = noProgSetup({ fight: true, attackers: 1, ...over, dyn });
  b.combatCfg = () => ({ interactKey: 'J', closeInSpell: 111 });
  b.pet = () => ({ exists: false, ttPlayer: true, ttPet: false });
  b.spellInRange = () => range;
  b.lastHealCastAt = () => 0;
  b.casting = () => false;
  b.targetOffscreen = () => offscreen;
  const taps = [], turns = [];
  b.tap = async k => { taps.push(k); };
  b.turnTo = async a => { turns.push(a); };
  b.hold = async () => { await new Promise(r => setTimeout(r, 40)); };   // как в игре: удержание клавиши занимает время
  return { b, taps, turns };
}

test('интеракт: цель вне дальности ближнего умения и урона нет — жмёт interactKey и пишет в журнал', async () => {
  const { b, taps } = interactSetup({ range: 0 });
  setTimeout(() => { b.running = false; }, 2600);
  await b.fight({ timeout: 5000 });
  assert.ok(taps.includes('J'));
  const rec = b.logs.find(l => String(l[2]).startsWith('интеракт: нажата (вне дальности)'));   // не раньше чем через 2 с после начала боя
  assert.ok(rec && rec[3].why === 'вне дальности');
});

test('интеракт: цель в дальности — не жмёт; ошибка «не лицом» — жмёт', async () => {
  const near = interactSetup({ range: 1 });
  setTimeout(() => { near.b.running = false; }, 2600);
  await near.b.fight({ timeout: 5000 });
  assert.equal(near.taps.includes('J'), false);
  const back = interactSetup({ range: 1, over: { err: 1, errTime: 5, time: 1 } });
  setTimeout(() => { back.b.running = false; }, 2600);
  await back.b.fight({ timeout: 5000 });
  assert.ok(back.taps.includes('J'));
  assert.ok(back.b.logs.some(l => String(l[2]).includes('(не лицом)')));
});

test('интеракт: мы наносим урон цели — не жмёт; повтор не чаще раза в 2,5 с', async () => {
  const hitting = interactSetup({ range: 0, dyn: t => ({ targetHp: 500 - t }) });
  setTimeout(() => { hitting.b.running = false; }, 2600);
  await hitting.b.fight({ timeout: 5000 });
  assert.equal(hitting.taps.includes('J'), false);
  const once = interactSetup({ range: 0 });
  setTimeout(() => { once.b.running = false; }, 3500);
  await once.b.fight({ timeout: 5000 });
  assert.equal(once.taps.filter(k => k === 'J').length, 1);
});

test('интеракт: без interactKey в профиле правило молчит; во время автоподхода не вертится и не бежит вручную', async () => {
  const none = interactSetup({ range: 0 });
  none.b.combatCfg = () => ({ closeInSpell: 111 });
  setTimeout(() => { none.b.running = false; }, 2600);
  await none.b.fight({ timeout: 5000 });
  assert.equal(none.taps.includes('J'), false);
  // цель вне экрана и наше HP падает (canTurn был бы true), но 3 с после интеракта доворота нет
  const walking = interactSetup({ range: 0, offscreen: true, over: { dyn: t => ({ hp: Math.max(60, 100 - 3 * t) }) } });
  setTimeout(() => { walking.b.running = false; }, 2600);
  await walking.b.fight({ timeout: 5000 });
  assert.ok(walking.taps.includes('J'));
  assert.equal(walking.turns.length, 0);
});

test('combatCfg паладина: interactKey = J', () => {
  const b = fresh(); b.profile = 'paladin';
  assert.equal(b.combatCfg().interactKey, 'J');
});

// --- «бой слишком долгий» не останавливает охоту, пока не повторится 3 раза за 30 минут ---
function longFightSetup() {
  const b = fresh();
  const chats = [];
  b.__win.chat = async c => { chats.push(c); };
  b.grinding = true;
  b.grindCfg = {};
  b.dead = () => false;
  b.fight = async () => ({ result: 'бой слишком долгий', hp: 100, hpMax: 100, tLvl: -1, tDist: 1, attackers: 1 });
  return { b, chats, st: { kills: 0 } };
}

test('бой слишком долгий: первые два раза охота продолжается (цель снимается), на третий за 30 минут — останавливается', async () => {
  const { b, chats, st } = longFightSetup();
  assert.equal(await b.fightAndLoot(st), null);
  assert.equal(await b.fightAndLoot(st), null);
  assert.deepEqual(chats, ['/cleartarget', '/cleartarget']);
  assert.equal(st.kills, 0);
  assert.equal(st.longFights, 2);
  assert.equal(await b.fightAndLoot(st), 'бой: бой слишком долгий (3 раза за 30 минут)');
  assert.ok(b.logs.some(l => String(l[2]).includes('продолжаю охоту (1 из 3')));
  assert.ok(b.logs.some(l => String(l[2]).includes('охота останавливается (3 из 3')));
});

test('бой слишком долгий: старые случаи (больше 30 минут назад) не считаются', async () => {
  const { b, st } = longFightSetup();
  b.longFightTimes = [Date.now() - 31 * 60000, Date.now() - 40 * 60000];
  assert.equal(await b.fightAndLoot(st), null);
  assert.equal(b.longFightTimes.length, 1);
});

// --- «бой без противника»: боевой флаг висит, а урона, цели и траты маны нет ---
function idleFightSetup(over = {}, dyn = null) {
  const b = noProgSetup({ fight: true, attackers: 0, target: false, enemy: false, targetHp: 0, ...over, dyn });
  b.grindCfg = { idleFightMs: 400, noProgressMs: 60000 };
  b.tabTarget = async () => { await new Promise(r => setTimeout(r, 30)); return b.state(); };
  b.switchToAttacker = async () => false;
  return b;
}

test('fight: боевой флаг висит, 20 с (тут 0,4 с) нет урона, цели и маны — «бой без противника»', async () => {
  const b = idleFightSetup();
  const r = await b.fight({ timeout: 10000 });
  assert.equal(r.result, 'бой без противника');
  assert.ok(r.idleMs >= 400);
});

test('fight: трата маны, урон по нам или нас бьют — это активность, «боя без противника» нет', async () => {
  for (const dyn of [t => ({ manaPct: 1e6 - t }), t => ({ hp: 1e6 - t }), () => ({ attackers: 1 })]) {   // значения только убывают: активность не прекращается
    const b = idleFightSetup({}, dyn);
    setTimeout(() => { b.running = false; }, 1200);
    const r = await b.fight({ timeout: 10000 });
    assert.notEqual(r.result, 'бой без противника');
  }
});

function idleGrindSetup() {
  const b = fresh();
  const chats = [], left = [];
  b.__win.chat = async c => { chats.push(c); };
  b.grinding = true; b.grindCfg = {}; b.dead = () => false;
  b.fight = async () => ({ result: 'бой без противника', idleMs: 21000, hp: 100, hpMax: 100, tLvl: 0, tDist: 0 });
  b.leaveCombat = async st => { left.push(st); return true; };
  return { b, chats, left, st: { kills: 0 } };
}

test('бой без противника: цель снимается, бот отходит, охота продолжается; 4-й раз за 10 минут — останавливается', async () => {
  const { b, chats, left, st } = idleGrindSetup();
  for (let i = 0; i < 3; i++) assert.equal(await b.fightAndLoot(st), null);
  assert.deepEqual(chats, ['/cleartarget', '/cleartarget', '/cleartarget']);
  assert.equal(left.length, 3);
  assert.equal(st.kills, 0);
  assert.equal(st.idleFights, 3);
  assert.equal(await b.fightAndLoot(st), 'бой: бой без противника (4 раза за 10 минут)');
  assert.ok(b.logs.some(l => String(l[2]).includes('отхожу и продолжаю охоту (1 из 4')));
  assert.equal(left.length, 3);                                   // на последнем разе не отходим — охота останавливается
});

test('бой без противника: случаи старше 10 минут не считаются', async () => {
  const { b, st } = idleGrindSetup();
  b.idleFightTimes = [Date.now() - 11 * 60000, Date.now() - 20 * 60000, Date.now() - 30 * 60000];
  assert.equal(await b.fightAndLoot(st), null);
  assert.equal(b.idleFightTimes.length, 1);
});

test('leaveCombat: поворачивается к якорю, бежит вперёд, ждёт сброса боевого флага', async () => {
  const b = fresh();
  b.grinding = true;
  let calls = 0;
  b.state = () => ({ x: 50, y: 50, hp: 100, dead: false, attackers: 0, fight: ++calls < 6 });
  const turns = [], keys = [];
  b.turnTo = async a => { turns.push(+a.toFixed(2)); };
  b.key = (k, on) => { keys.push(k + (on ? '+' : '-')); };
  const cleared = await b.leaveCombat({ anchor: { x: 50, y: 50.5 } }, 300);
  assert.equal(cleared, true);
  assert.equal(turns.length, 1);
  assert.equal(Math.abs(turns[0]), 3.14);         // якорь южнее (y больше): курс |π|, как в moveTo atan2(-dx, -dy)
  assert.deepEqual(keys, ['W+', 'W-']);
  assert.ok(b.logs.some(l => String(l[2]).includes('боевой флаг сброшен')));
});

// --- правила советника (96-advisor.js) ---
const inMs = ms => Date.now() + ms;

test('applyDirectives: принимает правильные правила и отбрасывает неправильные (вид, границы, радиус, срок)', () => {
  const b = fresh();
  const n = b.applyDirectives([
    { kind: 'avoid_spot', zone: 401, x: 79, y: 27, r: 1.4, until: inMs(60000) },
    { kind: 'penalize_spot', zone: 401, x: 81, y: 29, r: 1.4, until: inMs(60000) },
    { kind: 'avoid_point', zone: 401, x: 79.3, y: 26.5, r: 0.15, until: inMs(60000) },
    { kind: 'format_disk', zone: 401, x: 1, y: 1, r: 1, until: inMs(60000) },          // неизвестный вид
    { kind: 'avoid_spot', zone: 401, x: 120, y: 27, r: 1, until: inMs(60000) },        // вне карты
    { kind: 'avoid_spot', zone: 401, x: 50, y: 50, r: 30, until: inMs(60000) },        // радиус слишком большой
    { kind: 'avoid_spot', zone: 401, x: 50, y: 50, r: 1, until: Date.now() - 1 },      // срок вышел
    { kind: 'avoid_spot', zone: 'x', x: 50, y: 50, r: 1, until: inMs(60000) },         // не число
    null,
  ]);
  assert.equal(n, 3);
  assert.deepEqual(plain(b.directives.map(d => d.kind)), ['avoid_spot', 'penalize_spot', 'avoid_point']);
  assert.equal(b.applyDirectives('мусор'), 0);                                        // не список — очищает
  assert.equal(b.directives.length, 0);
});

test('directiveFactor: запрет → 0, штраф → 0.5, вне клетки или другая зона → 1, истёкшее правило не действует', () => {
  const b = fresh();
  b.applyDirectives([
    { kind: 'avoid_spot', zone: 401, x: 79, y: 27, r: 1.4, until: inMs(60000) },
    { kind: 'penalize_spot', zone: 401, x: 81, y: 29, r: 1.4, until: inMs(60000) },
    { kind: 'avoid_point', zone: 401, x: 60, y: 60, r: 0.15, until: inMs(60000) },
  ]);
  assert.equal(b.directiveFactor(401, 79.5, 27.5), 0);
  assert.equal(b.directiveFactor(401, 81, 29), 0.5);
  assert.equal(b.directiveFactor(401, 70, 70), 1);
  assert.equal(b.directiveFactor(115, 79, 27), 1);                                    // другая зона
  assert.equal(b.directiveFactor(401, 60, 60), 1);                                    // точка упора места охоты не меняет
  b.directives[0].until = Date.now() - 1;
  assert.equal(b.directiveFactor(401, 79.5, 27.5), 1);
});

test('applyDirectives: точка упора попадает в память упоров как надёжная; повтор не дублирует', () => {
  const b = fresh();
  const d = [{ kind: 'avoid_point', zone: 401, x: 79.3, y: 26.5, r: 0.15, until: inMs(60000) }];
  b.applyDirectives(d);
  b.applyDirectives(d);
  const pts = b.stuckList().filter(p => p.zone === 401);
  assert.equal(pts.length, 1);
  assert.ok(pts[0].n >= 2 && pts[0].persistent);
  assert.ok(b.stuckAhead({ zone: 401, x: 79.3, y: 26.55, facing: 0 }));                // стоит рядом с точкой и смотрит на неё
});

test('applyDirectives: в журнал пишется только при изменении набора правил', () => {
  const b = fresh();
  const d = [{ kind: 'avoid_spot', zone: 401, x: 79, y: 27, r: 1.4, until: inMs(60000) }];
  b.applyDirectives(d); b.applyDirectives(d);
  assert.equal(b.logs.filter(l => l[1] === 'advisor').length, 1);
  b.applyDirectives([]);
  assert.equal(b.logs.filter(l => l[1] === 'advisor').length, 2);
});

test('nextPointRadius: запрещённая советником точка пропускается, оштрафованная теряет половину веса', () => {
  const s = { x: 50, y: 50, zone: 401 }, anchor = { x: 50, y: 50 }, cfg = { radius: 3, respawnSec: 60 };
  // запрет: «жирная» точка в центре запрещена — берём другую (в пределах 1 ед. от якоря)
  const b = fresh();
  b.applyDirectives([{ kind: 'avoid_spot', zone: 401, x: 50, y: 50, r: 0.3, until: inMs(60000) }]);
  b.grindSpots = [{ x: 50, y: 50, kills: 100, empty: 0, visitedAt: 0 }, { x: 50.5, y: 50.5, kills: 5, empty: 0, visitedAt: 0 }];
  assert.equal(b.nextPointRadius(s, anchor, cfg).x, 50.5);
  // штраф только у первой точки (r 0.5 вокруг 50,50): её вес 6 / 0.5 * 0.5 = 6 всё ещё выше 4 / 1.2 = 3.3
  const c = fresh();
  c.applyDirectives([{ kind: 'penalize_spot', zone: 401, x: 50, y: 50, r: 0.5, until: inMs(60000) }]);
  c.grindSpots = [{ x: 50.2, y: 50, kills: 6, empty: 0, visitedAt: 0 }, { x: 50.9, y: 50, kills: 4, empty: 0, visitedAt: 0 }];
  assert.equal(c.nextPointRadius(s, anchor, cfg).x, 50.2);
  // а при 1 убийстве штраф переворачивает выбор: 1 / 0.5 * 0.5 = 1 против 4 / 1.2 = 3.3 (без штрафа выиграла бы первая: 1 / 0.5 = 2 < 3.3 — тоже вторая, поэтому берём 2 убийства: 2 / 0.5 = 4 > 3.3)
  c.grindSpots = [{ x: 50.2, y: 50, kills: 2, empty: 0, visitedAt: 0 }, { x: 50.9, y: 50, kills: 4, empty: 0, visitedAt: 0 }];
  assert.equal(c.nextPointRadius(s, anchor, cfg).x, 50.9);                            // со штрафом 2 / 0.5 * 0.5 = 2 < 3.3
  const d = fresh();
  d.grindSpots = c.grindSpots;
  assert.equal(d.nextPointRadius(s, anchor, cfg).x, 50.2);                            // без правил: 2 / 0.5 = 4 > 3.3
});

test('buildPatrolRoute: запрещённые советником точки в маршрут не попадают', () => {
  const b = fresh();
  b.state = () => ({ zone: 401, x: 50, y: 50 });
  b.applyDirectives([{ kind: 'avoid_spot', zone: 401, x: 50, y: 50, r: 1, until: inMs(60000) }]);
  b.grindSpots = [{ x: 50.2, y: 50, kills: 5, empty: 0 }, { x: 55, y: 50, kills: 5, empty: 0 }];
  const route = b.buildPatrolRoute({ x: 50, y: 50 }, { maxDistance: 20 });
  assert.equal(route.length, 1);
  assert.equal(route[0].x, 55);
});

// --- чат игры: ожидание открытия, закрытие зависшего поля, безопасные и повторные вызовы ---
// Имитация игры: Enter переключает поле ввода (как в игре) через openDelay мс, Escape закрывает; набранное складывается в typed.
function chatGame({ openDelay = 0, openOnEnter = () => true, startOpen = false } = {}) {
  const b = fresh();
  const w = b.__win, presses = [], typed = [];
  w.__gameEditMode = startOpen ? 1 : 0;
  let enters = 0;
  w.document.getElementById = () => ({
    dispatchEvent(ev) {
      if (ev.type !== 'keydown') return;
      presses.push(ev.code);
      if (ev.code === 'Escape') w.__gameEditMode = 0;
      if (ev.code === 'Enter') {
        enters++;
        if (!openOnEnter(enters)) return;
        const toggle = () => { w.__gameEditMode = w.__gameEditMode === 0 ? 1 : 0; };
        if (openDelay > 0) setTimeout(toggle, openDelay); else toggle();
      }
    },
  });
  w.wowKey = c => typed.push(c);
  return { b, w, presses, typed };
}

test('chat: поле открылось с задержкой 400 мс — ждём и жмём Enter один раз (раньше второй Enter закрывал открывшееся поле)', async () => {
  const { w, presses, typed } = chatGame({ openDelay: 400 });
  await w.chat('/startattack');
  assert.equal(presses.filter(c => c === 'Enter').length, 1);
  assert.equal(String.fromCodePoint(...typed.slice(0, -1)), '/startattack');
  assert.equal(typed[typed.length - 1], 13);                       // и отправлено Enter-ом
});

test('chat: поле уже открыто (зависло) — сначала Escape, потом обычное открытие', async () => {
  const { w, presses } = chatGame({ startOpen: true });
  await w.chat('/cleartarget');
  assert.equal(presses[0], 'Escape');
  assert.ok(presses.includes('Enter'));
});

test('chat: не открывается — 3 попытки и честная ошибка; chatSafe возвращает false и пишет в журнал, не роняя вызывающего', async () => {
  const g = chatGame({ openOnEnter: () => false });
  await assert.rejects(() => g.w.chat('/x'), /chat did not open/);
  assert.equal(g.presses.filter(c => c === 'Enter').length, 3);
  const h = chatGame({ openOnEnter: () => false });
  h.w.bot = h.b;
  assert.equal(await h.w.chatSafe('/startattack'), false);
  assert.ok(h.b.logs.some(l => l[1] === 'chat' && String(l[2]).includes('/startattack')));
  const ok = chatGame();
  assert.equal(await ok.w.chatSafe('/cleartarget'), true);
});

test('chatRetry: первая серия попыток провалилась, со второй вышло — команда отправлена; нет «не в мире»-повтора', async () => {
  // Enter 1..3 не открывают (первая серия), 4-й открывает
  const { w, typed } = chatGame({ openOnEnter: n => n >= 4 });
  await w.chatRetry('/run RepopMe()', 2);
  assert.equal(String.fromCodePoint(...typed.slice(0, -1)), '/run RepopMe()');
  const never = chatGame({ openOnEnter: () => false });
  await assert.rejects(() => never.w.chatRetry('/x', 2), /chat did not open/);
  const login = chatGame();
  login.w.document.getElementById = () => ({ type: 'password', dispatchEvent() {} });
  await assert.rejects(() => login.w.chatRetry('/x', 3), /not in world/);
});
