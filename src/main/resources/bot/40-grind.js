// 40-grind.js — цикл гринда: найти моба → подойти → бой (+ лут) → отдых → повтор.
// Сколько «слишком долгих» боёв за 30 минут останавливают охоту (меньше — снимаем цель и продолжаем)
const LONG_FIGHT_MAX = 3;
// «Бой без противника» (боевой флаг висит, нет ни урона, ни цели): сколько раз за 10 минут останавливают охоту (меньше — отходим и продолжаем)
const IDLE_FIGHT_MAX = 4;
Object.assign(bot, {
  grindCfg: {
    mode: 'radius',     // 'radius' — охота вокруг старта; 'patrol' — обход мест по кольцу
    maxDistance: 15,    // patrol: максимальное расстояние до мест маршрута (единицы карты)
    radius: 2.5,        // радиус охоты вокруг стартовой точки (единицы карты)
    minLvlDiff: -5,     // не трогать мобов слабее (серые — без опыта)
    maxLvlDiff: 2,      // и сильнее себя больше чем на 2 уровня
    restBelow: 0.7,     // отдыхать (и не начинать новый пулл), если HP ниже 70%
    petRestBelow: 0.5,  // отдыхать (лечить питомца), если HP питомца ниже 50% и в ротации есть его лечение
    manaBelow: 0.4,    // отдыхать, если мана ниже 40%
    pullKey: '3',       // умение для агро издалека
    healKey: '7',       // лечение заклинанием в отдыхе (v1)
    healSpell: null,     // лечение заклинанием в отдыхе (v2, по хешу названия)
    drinkKey: '0',      // питьё в отдыхе
    maxKills: 10,       // 0 = без лимита
    loot: false,        // собирать добычу после боя; false — просто убивать, без лута
    maxMinutes: 30,
    nameplates: true,   // включать полосы здоровья над врагами (/console nameplateShowEnemies 1): по ним проще найти цель курсором; false — не трогать
    mountTravel: true,  // перебежки между точками верхом (профиль с mountKey), от mountMinDist
    mountMinDist: 0.6,  // единицы карты (~30 м): ближе пешком
    respawnSec: 300,    // через сколько секунд место из базы снова считается «тёплым» (5 минут)
    minFreeSlots: 2,    // при <= этом значении — идти к торговцу (если задан)
    names: [],          // имена мобов для /target имя (Java берёт из mob_spawn)
    emptyAfterMs: 0,    // >0: столько мс без новой цели — место пустое (используется hunt)
    autoRevive: true,   // после смерти: отпустить дух, добежать до трупа, воскреснуть и продолжить (revive)
  },
  grindSpots: [],
  grindStats: null,

  // посчитать причину отказа от цели в статистике гринда (видно в get_state → grind.rejects)
  noteReject(reason) {
    const st = this.grindStats; if (!st || !reason || reason === 'нет цели') return;
    st.rejects = st.rejects || {}; st.rejects[reason] = (st.rejects[reason] || 0) + 1;
  },

  // Недостижимые цели (результат fight «цель недостижима»): бан на 60 с по месту игрока (±0.15) и имени цели (хеш из полос;
  // нет полос — по уровню). Живёт в памяти бота.
  banTarget(s) {
    const t = (this.plates && this.plates() || []).find(p => p.target);
    const list = this.unreachable = (this.unreachable || []).filter(b => b.until > Date.now());
    list.push({ hash: t ? t.nameHash : null, lvl: s.tLvl, x: s.x, y: s.y, until: Date.now() + 60000 });
    if (list.length > 30) list.shift();
    return list[list.length - 1];
  },
  isBannedTarget(s) {
    const list = this.unreachable;
    if (!list || !list.length || !s) return false;
    const now = Date.now();
    const t = (this.plates && this.plates() || []).find(p => p.target);
    return list.some(b => b.until > now && Math.hypot(b.x - s.x, b.y - s.y) < 0.15
        && (b.hash != null && t ? b.hash === t.nameHash : b.lvl === s.tLvl));
  },

  // null — цель подходит; иначе причина отказа (видна в get_state → grind.rejects):
  // мёртв / нейтральный / игрок / элита/редкий / занят / уровень / не зверь (+ вне радиуса, далеко для выстрела)
  targetProblem(s) {
    if (!s.target) return 'нет цели';
    if (s.targetHp === 0) return 'мёртв';
    if (this.isBannedTarget(s)) return 'недостижима';
    if (!s.enemy) return 'нейтральный';
    if (s.tFlags & 1) return 'игрок';
    if (s.tFlags & 2) return 'элита/редкий';
    if (s.tFlags & 4) return 'занят';          // кто-то уже бьёт этого моба
    if (s.tLvl > this.grindCfg.maxLvlDiff || s.tLvl < this.grindCfg.minLvlDiff) return 'уровень';
    const cc = this.combatCfg && this.combatCfg(), p = this.pet && this.pet();
    if (cc && cc.beastsOnly && p && p.exists && !p.tBeast) return 'не зверь';
    return null;
  },

  // Цель подходит. needRange (стрелок на бегу): ещё и достаётся выстрелом — иначе молча «пока нет», не отказ.
  // Далёкая подходящая цель — не мусор: до неё дойдёт approach(), поэтому по дальности её здесь не отбрасываем.
  goodPullTarget(s, { needRange = false } = {}) {
    const why = this.targetProblem(s);
    if (why) { this.noteReject(why); return false; }
    const cc = this.combatCfg && this.combatCfg();
    if (needRange && cc && cc.ranged && this.spellInRange(cc.shootSpell) !== -1) {
      return this.spellInRange(cc.shootSpell) === 1;
    }
    return true;
  },

  // Поиск по именам из базы мобов: Tab не видит нейтральных (жёлтых) мобов, а /target имя — видит.
  // Не чаще раза в 5 с, в бою не вызывается.
  async findByName() {
    const names = this.grindCfg.names || [];
    if (!names.length || this.state().fight) return null;
    const now = Date.now();
    if (now - (this.lastNameScan || 0) < 5000) return null;
    this.lastNameScan = now;
    this.nameCool = this.nameCool || {};
    for (const nm of names) {
      if (!this.grinding || this.state().fight) return null;
      if ((this.nameCool[nm] || 0) > now) continue;       // до этого моба не дошли — пока не пробуем
      await chat('/target ' + nm);
      await sleep(300);
      await this.waitFrames(2);
      const s = this.state();
      if (this.goodPullTarget(s)) {
        // /target имя берёт моба и за 40+ ярдов, и за спиной. Далёких (>28 ярдов, tDist & 1 == 0) по умолчанию не берём —
        // бот убегал за мобами очень далеко: цель снимаем, имя на паузу 15 с. grindCfg.nameFarOk = true вернёт прежнее поведение
        // (тогда approach() подбежит; не дошли — имя на паузу, см. approach)
        const far = !(s.tDist & 1);
        if (far && !this.grindCfg.nameFarOk) {
          if (this.grindStats) this.grindStats.byNameTooFar = (this.grindStats.byNameTooFar || 0) + 1;
          this.nameCool[nm] = Date.now() + 15000;
          await chat('/cleartarget');
          continue;
        }
        if (this.grindStats) {
          this.grindStats.byName = (this.grindStats.byName || 0) + 1;
          if (far) this.grindStats.byNameFar = (this.grindStats.byNameFar || 0) + 1;
        }
        this.farName = far ? nm : null;
        return s;
      }
    }
    return null;
  },

  // Tab по кругу: 3 нажатия, поворот ~60°, и так 6 раз (полный оборот)
  async findTarget({ quick = false, once = false } = {}) {
    if (once) {
      // патруль на точке: 3 Tab вперёд, один разворот на 180°, ещё 3 Tab, поиск по имени, один скан экрана — и дальше
      for (let turn = 0; turn < 2 && this.grinding; turn++) {
        for (let i = 0; i < 3 && this.grinding; i++) {
          const s = await this.tabTarget();
          { const why = this.targetProblem(s); if (!why) return s; this.noteReject(why); }
        }
        if (turn === 0) await this.turnTo(this.state().facing + Math.PI, 3500, () => !this.grinding);
      }
      if (!this.grinding) return null;
      { const byName = await this.findByName(); if (byName) return byName; }
      const r = await this.scanScreen({ minLvlDiff: this.grindCfg.minLvlDiff, maxLvlDiff: this.grindCfg.maxLvlDiff });
      if (r && r.found) return this.state();
      await chat('/cleartarget');
      return null;
    }
    if (quick) {
      // быстрый режим: полный оборот (3 × 120°), на каждом направлении 3 Tab; без lookAround.
      // Tab выделяет только мобов впереди — без поворотов бот не видел мобов сбоку/сзади
      // и убегал к старому месту мимо них.
      for (let turn = 0; turn < 3 && this.grinding; turn++) {
        for (let i = 0; i < 3 && this.grinding; i++) {
          const s = await this.tabTarget();
          { const why = this.targetProblem(s); if (!why) return s; this.noteReject(why); }
        }
        if (turn < 2) await this.turnTo(this.state().facing + 2.1, 2500, () => !this.grinding);   // ~120°
      }
      if (!this.grinding) return null;
      { const byName = await this.findByName(); if (byName) return byName; }
      await chat('/cleartarget');
      return null;
    }
    for (let turn = 0; turn < 2 && this.grinding; turn++) {
      for (let i = 0; i < 3 && this.grinding; i++) {
        const s = await this.tabTarget();
        { const why = this.targetProblem(s); if (!why) return s; this.noteReject(why); }
      }
      await this.hold('A', 330); await sleep(150);
    }
    if (!this.grinding) return null;
    { const byName = await this.findByName(); if (byName) return byName; }
    const r = await this.lookAround({ minLvlDiff: this.grindCfg.minLvlDiff, maxLvlDiff: this.grindCfg.maxLvlDiff });
    if (r && r.found) return this.state();
    await chat('/cleartarget');
    return null;
  },

  // враждебный моб подходящего уровня ближе ~10 м (tDist & 2): текущая цель или ещё до 2 Tab
  async nearbyHostile() {
    const ok = s => !this.targetProblem(s) && (s.tDist & 2);
    if (ok(this.state())) return true;
    for (let i = 0; i < 2 && this.grinding; i++) {
      if (ok(await this.tabTarget())) return true;
    }
    return false;
  },

  // Стрелку нужен питомец. Нет — вызвать (клавиша petCallKey, каст ~5 с); не вышло (питомец погиб — после смерти
  // охотника это обычное дело) — «Воскрешение питомца» (revivePetSpell, нужна на панели с клавишей, каст ~10 с).
  // 'ok' — питомец есть или данных нет (старый Lua / не стрелок); 'dead' — мёртв и воскресить не вышло (гринд на паузу);
  // 'none' — вызвать не удалось. В бою не колдуем.
  async ensurePet(cc) {
    const p = this.pet();
    if (!p || !cc || !cc.petCallKey) return 'ok';
    const alive = () => { const q = this.pet(); return !!(q && q.exists && !q.dead); };
    if (alive() || this.state().fight) return 'ok';
    const stopped = () => !this.grinding && this.job.name !== 'revive' && this.job.name !== 'summonPet';
    const waitCast = async () => {
      await sleep(600);
      await this.waitFor(() => !this.casting(), 15000, 250, stopped);
      await sleep(800);
    };
    // 1) питомца нет совсем — призыв
    if (!p.exists) {
      await this.tap(cc.petCallKey);
      await waitCast();
      if (alive()) return 'ok';
    }
    // 2) мёртв (или призыв не помог) — воскрешение питомца
    const rec = cc.revivePetSpell && (this.spells() || []).find(r => r.nameHash === cc.revivePetSpell);
    if (rec && rec.ready === 1) {
      if (await this.pressSpell(cc.revivePetSpell)) {
        this.warnings.push('воскрешаю питомца');
        if (this.warnings.length > 20) this.warnings.shift();
        await waitCast();
        if (alive()) return 'ok';
      }
    } else {
      this.warnings.push(rec ? 'воскрешение питомца не готово' : 'нет «Воскрешения питомца» на панели умений — положи на панель с клавишей');
      if (this.warnings.length > 20) this.warnings.shift();
    }
    const q = this.pet();
    return q && q.exists && q.dead ? 'dead' : 'none';
  },

  // Перебежка к точке верхом: далеко (> mountMinDist, ~30 м) и вне боя — сесть на коня (профиль с mountKey; у остальных
  // mountUp ничего не делает), добежать, слезть. Слезаем всегда после moveTo — в том числе когда по дороге напали или
  // нашлась цель: бой и подход начинаются пешком. Выключить: mountTravel: false.
  async travelTo(x, y, o, cfg) {
    const s = this.state();
    const dist = Math.hypot(s.x - x, s.y - y);
    let tried = false, mountOk = null, res = null;
    const t0 = Date.now();
    if (cfg && cfg.mountTravel !== false && !s.fight && dist > ((cfg && cfg.mountMinDist) || 0.6)) {
      tried = true;
      try { await this.mountUp(); } catch (e) { /* не вышло сесть — бежим пешком */ }
      mountOk = !!(this.mounted && this.mounted());
    }
    const rideT0 = Date.now();
    try { res = await this.moveTo(x, y, o); return res; }
    finally {
      const stillMounted = !!(this.mounted && this.mounted());
      if (stillMounted) await this.dismount();
      // диагностика перебежки верхом: сел ли, чем кончился бег, сколько ехали, слезали ли
      if (tried && this.log) this.log('info', 'travel', 'перебежка верхом: ' + (mountOk ? 'сел' : 'не сел') + ', бег: ' + (res && res.result || 'ошибка'),
          { dist: +dist.toFixed(2), mountOk, result: res && res.result || null, mountMs: rideT0 - t0, rideMs: Date.now() - rideT0, dismounted: stillMounted });
    }
  },

  // Баффы профиля (cc.buffs): вне боя, не во время каста, с маной. Время последнего нажатия — this.buffAt[key];
  // после воскрешения оно сбрасывается (баффы слетают). Ошибка нажатия повторяется не раньше чем через минуту.
  async ensureBuffs(cc) {
    if (!cc || !cc.buffs || !cc.buffs.length) return;
    this.buffAt = this.buffAt || {};
    const stopped = () => !this.grinding && this.job.name !== 'revive' && this.job.name !== 'summonPet';
    for (const b of cc.buffs) {
      if (stopped()) return;
      const s = this.state();
      if (s.fight || s.dead || s.ghost || this.casting()) return;
      if (s.manaPct < 0.15) return;
      if (Date.now() - (this.buffAt[b.key] || 0) < b.everyMs) continue;
      this.buffAt[b.key] = Date.now();
      await this.tap(b.key);
      await sleep(600);
      await this.waitFor(() => !this.casting(), 8000, 250, stopped);
      await sleep(1600);                                   // общая перезарядка между баффами
      if (this.state().fight) return;
    }
  },

  // Отдельная задача «призови питомца": нет питомца — вызвать, мёртв — воскресить; уже с нами — ничего не делать.
  async summonPet() {
    const cc = this.combatCfg && this.combatCfg();
    if (!cc || !cc.petCallKey) return { result: 'у этого профиля нет питомца', ...this.state() };
    const p = this.pet();
    if (!p) return { result: 'нет данных о питомце (перезагрузи бота)', ...this.state() };
    if (p.exists && !p.dead) return { result: 'питомец уже с тобой', ...this.state() };
    if (this.state().fight) return { result: 'идёт бой — питомца не призываю', ...this.state() };
    const r = await this.ensurePet(cc);
    return { result: r === 'ok' ? 'питомец призван' : r === 'dead' ? 'питомец мёртв, воскресить не вышло' : 'не удалось призвать питомца',
             ...this.state() };
  },

  // питомцу пора на лечение: есть правило лечения питомца, питомец жив, HP низкое, лечение не висит.
  // Не чаще раза в 90 с: если лечение не удаётся, гринд не должен вечно сидеть в отдыхе.
  petNeedsRest(cfg) {
    if (!this.activeRotation().some(r => r.maxPetHpPct && r.spell)) return false;
    if (Date.now() - (this.lastPetRestAt || 0) < 90000) return false;
    const p = this.pet();
    return !!p && p.exists && !p.dead && !p.mend && p.hpPct < (cfg.petRestBelow ?? 0.5);
  },

  // насколько близко цель по признакам игры: 0 — дальше 28 ярдов, 1 — ближе 28, 2 — ближе 11 ярдов
  proximity() {
    const d = this.state().tDist;
    return (d & 2) ? 2 : (d & 1) ? 1 : 0;
  },

  // подойти, пока умение агро не станет доступно (цель в его радиусе)
  // подбежать к цели; если это была далёкая цель из findByName и дойти не вышло — имя на паузу 45 с
  async approach(maxMs = 6000) {
    const far = this.farName; this.farName = null;
    const t0 = Date.now();
    let ok;
    this.approachWhy = null; this.approachSteers = 0; this.approachScans = 0; this.approachFallback = false; this.approachSteered = false; this.approachAim = null;
    this.approachStage = null; this.approachTurns = 0; const prox0 = this.proximity();
    if (far) {
      // далёкая цель: бежим до 15 с, пока персонаж реально движется и не вышел за радиус охоты от якоря
      const st = this.grindStats, cfg = this.grindCfg || {};
      const limit = st && st.mode === 'patrol' ? cfg.maxDistance : cfg.radius;
      const anchor = st && st.anchor && limit ? st.anchor : null;
      if (this.steerSupported()) {
        // есть проверка «курсор над целью»: находим цель на экране, поворачиваемся и подруливаем на бегу
        try { ok = await this.farRunSteered(anchor, limit, 15000); } finally { this.key('W', false); this.key('A', false); this.key('D', false); }
        if (!ok && this.approachWhy === 'цель не на экране' && this.grinding) {
          // не нашли цель на экране — бежим прямо к ней, как в старом режиме
          this.approachWhy = null; this.approachFallback = true;
          this.farApproach = { anchor, limit };
          try { ok = await this._approach(Math.max(maxMs, 12000)); } finally { this.farApproach = null; }
        }
      } else {
        // старый Lua: бежим прямо, сдаёмся при остановке или выходе за радиус
        this.farApproach = { anchor, limit };
        try { ok = await this._approach(Math.max(maxMs, 15000)); } finally { this.farApproach = null; }
      }
    } else if (this.steerSupported() && !(this.state().tDist & 2) && !this.state().fight) {
      // обычная цель (Tab, на бегу, на точке): Tab берёт врага и сбоку, а голый бег по прямой уводил от него.
      // Находим цель на экране, поворачиваемся и подруливаем на бегу — как для далёких; не нашли — бежим прямо, как раньше
      this.approachSteered = true;
      try { ok = await this.farRunSteered(null, null, Math.max(maxMs, 8000), { turn: false }); } finally { this.key('W', false); this.key('A', false); this.key('D', false); }
      if (!ok && this.approachWhy === 'цель не на экране' && this.grinding) {
        // не нашли цель на экране даже плотным сканом: не бежим вслепую 10 с, а короткими кусками (1 с) с повторным
        // плотным сканом после каждого; нашли — довернули и бежим дальше; за 3 куска не дошли — сдаёмся
        this.approachWhy = null; this.approachFallback = true;
        const abort = () => !this.grinding;
        for (let r = 0; r < 3 && !ok && this.grinding; r++) {
          try { ok = await this._approach(1000); } finally { this.key('W', false); }
          if (ok || !this.grinding) break;
          const g = await this.scanOnScreen({ dense: true, abort });
          if (g) await this.aimBy(g.fx, abort);
        }
        if (!ok && !this.approachWhy) this.approachWhy = 'нет сближения';
      }
    } else {
      ok = await this._approach(maxMs);
    }
    if (!far && this.approachSteered && this.log) {
      this.log(ok ? 'info' : 'warn', 'approach', 'подход с подруливанием: ' + (ok ? 'дошёл' : (this.approachWhy || 'не дошёл')),
        { steered: true, ok: !!ok, why: this.approachWhy || null, durMs: Date.now() - t0, steers: this.approachSteers || 0, scans: this.approachScans || 0, scanMs: this.approachScanMs || 0, dense: !!this.approachDense, fallback: !!this.approachFallback, aim: this.approachAim || null });
    }
    if (far) {
      const why = ok ? 'дошёл' : (this.approachWhy || 'время вышло');
      if (this.log) this.log(ok ? 'info' : 'warn', 'approach', 'далёкая цель: ' + why, { far: true, ok: !!ok, why, durMs: Date.now() - t0, steers: this.approachSteers || 0, scans: this.approachScans || 0, fallback: !!this.approachFallback, aim: this.approachAim || null,
          stage: this.approachStage || null, turns: this.approachTurns || 0, prox0, prox1: this.proximity() });
    }
    if (far && !ok) {
      this.nameCool = this.nameCool || {};
      this.nameCool[far] = Date.now() + 45000;
      if (this.grindStats) this.grindStats.farFails = (this.grindStats.farFails || 0) + 1;
    }
    return ok;
  },

  // ---- далёкая цель: найти её на экране курсором, повернуться и бежать с подруливанием ----
  // Ищем курсором по полосе экрана: Q[14] === 2, когда под курсором именно наша цель. Возвращает {fx, fy} или null.
  // dense — плотный скан по центральной зоне экрана (строки и выше модели: там полоса здоровья над врагом) — когда обычный не нашёл
  async scanOnScreen({ narrow = false, dense = false, abort = null } = {}) {
    // полоса по центру — частый шаг (цель вдали маленькая), выше и ниже — редкий
    const plan = dense ? [[0.5, 0.04], [0.44, 0.04], [0.56, 0.04], [0.38, 0.04], [0.32, 0.04]]
      : narrow ? [[0.46, 0.04], [0.54, 0.04]] : [[0.5, 0.03], [0.42, 0.06], [0.58, 0.06]];
    const lo = dense ? 0.2 : narrow ? 0.28 : 0.04, hi = dense ? 0.8 : narrow ? 0.72 : 0.96;
    for (const [fy, step] of plan) {
      const cols = [];
      for (let fx = lo; fx <= hi + 1e-9; fx += step) cols.push(fx);
      cols.sort((a, b) => Math.abs(a - 0.5) - Math.abs(b - 0.5));        // от центра к краям
      for (const fx of cols) {
        if (abort && abort()) return null;
        this.mouse(fx, fy); await sleep(45);
        if (this.overTarget()) return { fx, fy };
      }
    }
    return null;
  },

  // повернуться так, чтобы цель, стоящая на экране в доле fx, оказалась по центру (≈ 1.6 рад на весь экран; потом подправим)
  // Возвращает {expected, actual} — на сколько рад хотели и на сколько реально повернулись (для калибровки коэффициента 1.6).
  async aimBy(fx, abort = null) {
    const d = -(fx - 0.5) * 1.6;                                        // справа от центра — вправо (facing уменьшается)
    if (Math.abs(d) <= 0.1) return null;
    const norm = v => Math.atan2(Math.sin(v), Math.cos(v));
    const f0 = this.state().facing;
    await this.turnTo(f0 + d, 1500, abort);
    return { expected: +d.toFixed(2), actual: +norm(this.state().facing - f0).toFixed(2) };
  },

  // Бежим к далёкой цели: 1) ищем её на экране (до 4 поворотов по ~90°), 2) бежим кусками по 1.2 с и после каждого
  // подруливаем по положению цели на экране. Сдаёмся: цель не нашли, потеряли, упёрлись, вышли за радиус, 15 с.
  // opts.turn === false (обычная цель из Tab: она впереди): не поворачиваемся вслепую на 86°, а повторяем скан плотнее
  async farRunSteered(anchor, limit, maxMs, opts = {}) {
    const t0 = Date.now(), abort = () => !this.grinding;
    this.approachSteers = 0;
    // Сначала полоса здоровья цели (lua/plates.lua): направление известно сразу, без поиска курсором. Нет полосы (цель вне экрана
    // или старый Lua) — ищем курсором, как раньше.
    let f = this.plateAim(), dense = false, stage = f ? 'полоса' : 'обычный';
    if (!f) f = await this.scanOnScreen({ narrow: false, abort });
    this.approachScans = f && f.plate ? 0 : 1; this.approachTurns = 0;
    if (!f && this.grinding) {
      dense = true; stage = 'плотный';
      f = await this.scanOnScreen({ dense: true, abort });
      this.approachScans = 2;
      if (opts.turn !== false) {
        // далёкая цель из /target Имя бывает где угодно (и за спиной): полный осмотр — до трёх поворотов по 90° с обычным
        // сканом после каждого; нашли — доворот и бег. Раньше был один слепой поворот и бег прямо вслепую
        for (let k = 1; k <= 3 && !f && this.grinding; k++) {
          await this.turnTo(this.state().facing + Math.PI / 2, 2500, abort);
          this.approachTurns = k;
          f = await this.scanOnScreen({ narrow: false, abort });
          this.approachScans++;
          if (f) stage = 'поворот ' + k;
        }
      }
    }
    this.approachStage = f ? stage : 'не нашёл';
    this.approachScanMs = Date.now() - t0; this.approachDense = dense;
    { const gs = this.grindStats;                          // статистика поиска цели на экране — видна в статусе
      if (gs) { const k = f ? (dense ? 'scanDense' : 'scanFound') : 'scanMiss'; gs[k] = (gs[k] || 0) + 1; } }
    if (!f) { this.approachWhy = 'цель не на экране'; return false; }
    const aim0 = await this.aimBy(f.fx, abort);
    // уточнение: грубый поворот по экрану неточен (1.6 рад на экран — примерно). Узкий скан по центру: если цель не по центру,
    // подправляем ещё раз (до 2), потом бежим. Не нашли в узкой полосе — дальше подруливание широким сканом в цикле
    this.approachAim = { fx0: +f.fx.toFixed(2), fx1: +f.fx.toFixed(2), refines: 0, expected: aim0 ? aim0.expected : 0, actual: aim0 ? aim0.actual : 0, src: f.plate ? 'plate' : 'scan' };
    for (let r = 0; r < 2 && this.grinding; r++) {
      // по полосе — перечитываем её положение (Lua обновляет таблицу ~10 раз в секунду); без полосы — узкий скан курсором
      let g;
      if (f.plate) { await sleep(150); g = this.plateAim(); } else g = await this.scanOnScreen({ narrow: true, abort });
      if (!g) break;
      this.approachAim.fx1 = +g.fx.toFixed(2);
      if (Math.abs(g.fx - 0.5) <= 0.06) break;                          // по центру
      await this.aimBy(g.fx, abort); this.approachAim.refines++;
    }
    // проверка точности наведения по полосе: курсором (узкая полоса по центру) — совпала ли цель; только первые 40 подходов
    if (f.plate && (this.plateChecks || 0) < 40 && this.grinding) {
      this.plateChecks = (this.plateChecks || 0) + 1;
      const chk = await this.scanOnScreen({ narrow: true, abort });
      this.approachAim.check = chk ? +chk.fx.toFixed(2) : null;           // null — курсор по центру цель не нашёл
    }
    let s0 = this.state(), pos = { x: s0.x, y: s0.y }, stuck = 0;
    const avoid = { n: 0, abort };
    let bestPr = this.proximity(), lastGainAt = Date.now();
    while (this.grinding && Date.now() - t0 < maxMs) {
      if (await this._approach(1200)) return true;
      const s = this.state();
      if (s.fight) return true;
      // не стало ближе (всё ещё дальше 28 ярдов) за 8 с: бежим не туда — сдаёмся, а не бегаем до конца времени
      { const pr = this.proximity();
        if (pr > bestPr) { bestPr = pr; lastGainAt = Date.now(); }
        else if (pr === 0 && Date.now() - lastGainAt > 8000) { this.approachWhy = 'нет сближения'; this.key('W', false); return false; } }
      if (anchor && limit && Math.hypot(s.x - anchor.x, s.y - anchor.y) > limit) { this.approachWhy = 'вышел за радиус'; return false; }
      if (Math.hypot(s.x - pos.x, s.y - pos.y) < 0.02) { if (++stuck >= 2) { this.noteStuck(s, 'подход'); this.approachWhy = 'упёрся'; return false; } } else stuck = 0;
      pos = { x: s.x, y: s.y };
      this.key('W', true);                                              // не стоим, пока ищем цель
      let g = this.plateAim() || await this.scanOnScreen({ narrow: true, abort });
      if (!g) g = await this.scanOnScreen({ narrow: false, abort });
      if (!g) { this.key('W', false); this.approachWhy = 'потерял цель'; return false; }
      this.approachSteers++;
      await this.aimBy(g.fx, abort);
      // впереди запомненное место упора — обходим сбоку и снова наводимся на цель
      if (await this.avoidStuck(this.state(), avoid)) {
        const g2 = this.plateAim() || await this.scanOnScreen({ narrow: true, abort }) || await this.scanOnScreen({ narrow: false, abort });
        if (g2) await this.aimBy(g2.fx, abort);
      }
    }
    this.key('W', false);
    if (!this.approachWhy) this.approachWhy = 'время вышло';
    return false;
  },

  async _approach(maxMs = 6000) {
    // «пора сдаваться»: обычная цель — за 3.5 с не стало ближе (ступени 28/11 ярдов);
    // далёкая цель (farApproach) — персонаж не движется 2 с (упёрся) или вышел за радиус охоты от якоря
    const fa = this.farApproach;
    const mkGiveUp = () => {
      const s0 = this.state();
      let best = this.proximity(), lastGain = Date.now(), mv = { x: s0.x, y: s0.y, t: Date.now() };
      return () => {
        const s = this.state(), now = Date.now();
        if (fa) {
          if (fa.anchor && Math.hypot(s.x - fa.anchor.x, s.y - fa.anchor.y) > fa.limit) { this.approachWhy = 'вышел за радиус'; return true; }
          if (Math.hypot(s.x - mv.x, s.y - mv.y) > 0.02) mv = { x: s.x, y: s.y, t: now };
          else if (now - mv.t > 2000) { this.noteStuck(s, 'бег к далёкой цели'); this.approachWhy = 'упёрся'; return true; }
          // слепой бег: за 7 с не стало ближе 28 ярдов — бежим не туда, не тратим остальные ~10 с
          const prf = this.proximity();
          if (prf > best) { best = prf; lastGain = now; }
          else if (prf === 0 && now - lastGain > 7000) { this.approachWhy = 'нет сближения'; return true; }
          return false;
        }
        const pr = this.proximity();
        if (pr > best) { best = pr; lastGain = now; } else if (now - lastGain > 3500) { this.approachWhy = 'нет сближения'; return true; }
        return false;
      };
    };
    // стрелок: идти, пока цель не достаётся выстрелом, и сразу остановиться (вплотную не подбегать)
    const cc = this.combatCfg && this.combatCfg();
    if (cc && cc.ranged && this.spellInRange(cc.shootSpell) !== -1) {
      const t1 = Date.now(), giveUp = mkGiveUp();
      this.key('W', true);
      while (this.grinding && Date.now() - t1 < maxMs) {
        if (this.state().fight || this.spellInRange(cc.shootSpell) === 1) break;
        // бежим прямо; сдаёмся по giveUp (цель за спиной, обрыв, препятствие, радиус)
        if (giveUp()) break;
        await sleep(100);
      }
      this.key('W', false);
      return this.state().fight || this.spellInRange(cc.shootSpell) === 1;
    }
    // открыватель боя (паладин: Длань возмездия): бежим, пока она не достаёт до цели, и сразу останавливаемся
    const pr0 = this.pullRec(cc);
    if (pr0 && pr0.inRange !== -1) {
      const t1 = Date.now(), giveUp = mkGiveUp();
      const inRange = () => { const r = this.pullRec(cc); return !!r && r.inRange === 1; };
      this.key('W', true);
      while (this.grinding && Date.now() - t1 < maxMs) {
        if (this.state().fight || inRange()) break;
        if (giveUp()) break;
        await sleep(100);
      }
      this.key('W', false);
      return this.state().fight || inRange();
    }
    const isReady = k => (this.state().ready >> (+k - 1)) & 1;
    const t0 = Date.now(), giveUp = mkGiveUp();
    this.key('W', true);
    while (this.grinding && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2)) break;
      if (giveUp()) break;
      await sleep(150);
    }
    this.key('W', false);
    const s = this.state();
    return s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2);
  },

  // Выход из «боя без противника»: отходим от места (к якорю охоты, если далеко от него, иначе в случайную сторону) и ждём, пока боевой флаг
  // сбросится. Бежим вручную: moveTo в бою сразу возвращает «бой».
  async leaveCombat(st, runMs = 4000) {
    const abort = () => !this.grinding;
    const s0 = this.state(), anchor = st && st.anchor;
    const toAnchor = anchor && Math.hypot(s0.x - anchor.x, s0.y - anchor.y) > 0.25;
    const ang = toAnchor ? Math.atan2(-(anchor.x - s0.x), -(anchor.y - s0.y)) : Math.random() * 2 * Math.PI;
    const t0 = Date.now();
    await this.turnTo(ang, 2500, abort);
    this.key('W', true);
    try {
      while (!abort() && Date.now() - t0 < runMs) {
        await sleep(200);
        const s = this.state();
        if (s.dead || s.hp === 0 || (s.attackers != null && s.attackers >= 1)) break;     // напали — дальше разберётся бой
      }
    } finally { this.key('W', false); }
    const cleared = await this.waitFor(() => !this.state().fight, 12000, 500, abort);
    if (this.log) this.log('info', 'fight', 'отход после боя без противника: ' + (cleared ? 'боевой флаг сброшен' : 'флаг всё ещё висит'),
        { toAnchor: !!toAnchor, cleared, movedMs: Date.now() - t0 });
    return cleared;
  },

  // бой (+ лут, если grindCfg.loot); возвращает null если всё ок, иначе причину для выхода из гринда
  async fightAndLoot(st) {
    if (!this.grinding) return 'остановлен вручную';   // fight() сам включает running — после stop() не начинать бой
    // fleeAt: 0 — в автономной охоте не сдаёмся при HP < 20%: именно тогда нужны аварийные правила ротации
    // (лечение, ловушка), а остановка боя с «нужен выбор» оставляла персонажа без управления и без воскрешения
    const f = await this.fight({ fleeAt: 0 });
    if (this.dead() || f.hp === 0 || f.result === 'персонаж погиб') return null;
    if (f.result === 'притворился мёртвым') return null;    // спасся: гринд отдохнёт (HP низкое) и продолжит   // погибли: гринд-цикл увидит смерть на следующем шаге
    if (!this.grinding) return 'остановлен вручную';
    if (f.result === 'цель недостижима') {
      // не стоим перед недостижимой целью: бан на 60 с, цель снимаем, охота идёт дальше
      const b = this.banTarget(f);
      st.unreachable = (st.unreachable || 0) + 1;
      if (this.log) this.log('warn', 'fight', 'цель недостижима — бан 60 с', { waitedMs: f.waitedMs, err: f.err, errTime: f.errTime, tDist: f.tDist, tLvl: f.tLvl, nameHash: b.hash, targetHpPct: f.targetHpPct, total: st.unreachable });
      await chat('/cleartarget');
      return null;
    }
    if (f.result === 'бой без противника') {
      // боевой флаг висит, а противника нет ≥ 20 с: снимаем цель, отходим, ждём сброса флага — и продолжаем охоту.
      // Останавливаемся, только если так случилось IDLE_FIGHT_MAX раз за 10 минут.
      const now = Date.now();
      this.idleFightTimes = (this.idleFightTimes || []).filter(t => now - t < 10 * 60000);
      this.idleFightTimes.push(now);
      const n = this.idleFightTimes.length;
      st.idleFights = (st.idleFights || 0) + 1;
      if (this.log) this.log('warn', 'fight', 'бой без противника — ' + (n >= IDLE_FIGHT_MAX ? 'охота останавливается' : 'отхожу и продолжаю охоту') + ' (' + n + ' из ' + IDLE_FIGHT_MAX + ' за 10 мин)',
          { n, max: IDLE_FIGHT_MAX, idleMs: f.idleMs, tLvl: f.tLvl, tDist: f.tDist, hp: f.hpMax ? Math.round(f.hp / f.hpMax * 100) : null });
      if (n >= IDLE_FIGHT_MAX) return 'бой: бой без противника (' + n + ' раза за 10 минут)';
      await chat('/cleartarget');
      await this.leaveCombat(st);
      return null;
    }
    if (f.result === 'бой слишком долгий') {
      // Долгий бой без прогресса (зависший боевой флаг, цель недостижима) не должен останавливать всю охоту: снимаем цель и идём дальше.
      // Останавливаемся, только если так случилось LONG_FIGHT_MAX раз за 30 минут — значит, что-то сломано системно.
      const now = Date.now();
      this.longFightTimes = (this.longFightTimes || []).filter(t => now - t < 30 * 60000);
      this.longFightTimes.push(now);
      const n = this.longFightTimes.length;
      st.longFights = (st.longFights || 0) + 1;
      if (this.log) this.log('warn', 'fight', 'бой слишком долгий — ' + (n >= LONG_FIGHT_MAX ? 'охота останавливается' : 'продолжаю охоту') + ' (' + n + ' из ' + LONG_FIGHT_MAX + ' за 30 мин)',
          { n, max: LONG_FIGHT_MAX, tLvl: f.tLvl, tDist: f.tDist, attackers: f.attackers, hp: f.hpMax ? Math.round(f.hp / f.hpMax * 100) : null });
      if (n >= LONG_FIGHT_MAX) return 'бой: бой слишком долгий (' + n + ' раза за 30 минут)';
      await chat('/cleartarget');
      return null;
    }
    if (f.result !== 'бой окончен') return 'бой: ' + f.result;
    st.kills++;
    if (this.grindCfg.loot) {
      const l = await this.loot();
      st.lootResults = st.lootResults || {};                       // сколько раз какой был исход — видно в get_state
      st.lootResults[l.result] = (st.lootResults[l.result] || 0) + 1;
      if (l.result === 'добыча собрана') st.loots++;
      else if (l.result.startsWith('окно добычи')) return 'окно добычи не закрывается — сумки полные?';
      else if (l.result === 'бой во время сбора') return this.fightAndLoot(st);
    }
    return null;
  },

  // radius: лучшее «тёплое» место внутри радиуса охоты: много убийств, близко, давно не были, не пустовало
  nextPointRadius(s, anchor, cfg) {
    const now = Date.now();
    let best = null, bestScore = 0;
    for (const p of this.grindSpots || []) {
      if (Math.hypot(p.x - anchor.x, p.y - anchor.y) > Math.min(cfg.radius, 1.0)) continue;   // вне зоны охоты (не дальше ~50 м от места старта)
      if (p.visitedAt && now - p.visitedAt < cfg.respawnSec * 1000) continue;      // ещё не респавнулось
      const dist = Math.hypot(p.x - s.x, p.y - s.y) + 0.3;                        // от текущей позиции
      const score = p.kills * Math.pow(0.5, p.empty) / dist;                    // пустые места теряют вес
      if (score > bestScore) { bestScore = score; best = p; }
    }
    return best;
  },

  // patrol: кольцевой маршрут — жадно «ближайшее следующее» от текущей позиции,
  // только места не дальше maxDistance от anchor (до 150 мест)
  buildPatrolRoute(anchor, cfg) {
    const left = (this.grindSpots || [])
        .filter(p => Math.hypot(p.x - anchor.x, p.y - anchor.y) <= cfg.maxDistance)
        .slice(0, 150);
    const route = [];
    let cur = { x: anchor.x, y: anchor.y };
    while (left.length) {
      let bi = 0, bd = Infinity;
      for (let i = 0; i < left.length; i++) {
        const d = Math.hypot(left[i].x - cur.x, left[i].y - cur.y);
        if (d < bd) { bd = d; bi = i; }
      }
      cur = left.splice(bi, 1)[0];
      route.push(cur);
    }
    return route;
  },

  // patrol: следующее место по кольцу. Места моложе respawnSec пропускаем;
  // если остыли все — идём к самому давнему (без случайного блуждания)
  nextPointPatrol(s, anchor, cfg) {
    const route = this.patrolRoute || [];
    if (!route.length) return null;
    const now = Date.now();
    const hot = p => p.visitedAt && now - p.visitedAt < cfg.respawnSec * 1000;
    const from = this.patrolIdx || 0;
    for (let k = 0; k < route.length; k++) {
      const i = (from + k) % route.length;
      if (hot(route[i])) continue;
      if (i < from) this.patrolLaps = (this.patrolLaps || 0) + 1;   // кольцо замкнулось
      this.patrolIdx = i;
      return route[i];
    }
    let oldest = route[0];
    for (const p of route) if ((p.visitedAt || 0) < (oldest.visitedAt || 0)) oldest = p;
    this.patrolIdx = route.indexOf(oldest);
    return oldest;
  },

  // после осмотра места считаем посещёнными и соседние места (Tab берёт ~40 ярдов ≈ 0,7 карты): один и тот же ходящий моб
  // оставляет убийства в соседних клетках — это один район, а не несколько мест
  markVisited(spot, radius = 0.6) {
    const now = Date.now();
    spot.visitedAt = now;
    for (const p of this.grindSpots || []) {
      if (p !== spot && Math.hypot(p.x - spot.x, p.y - spot.y) < radius) p.visitedAt = now;
    }
  },

  async grind(opts = {}) {
    const cfg = Object.assign(this.grindCfg, opts || {});
    const mode = (opts && opts.mode) === 'patrol' ? 'patrol' : 'radius'; // по умолчанию radius
    cfg.names = (opts && opts.names) || [];
    cfg.emptyAfterMs = (opts && opts.emptyAfterMs) || 0;
    this.grinding = true;
    if (cfg.nameplates !== false) { try { await chat('/console nameplateShowEnemies 1'); } catch (e) { /* не критично */ } }
    const anchor = this.state(), t0 = Date.now();
    this.grindSpots = (opts.spots || []).map(p => ({ ...p, visitedAt: 0, empty: 0 }));
    this.patrolIdx = 0;
    this.patrolLaps = 0;
    this.patrolRoute = mode === 'patrol' ? this.buildPatrolRoute(anchor, cfg) : [];
    const st = this.grindStats = {
      mode,
      kills: 0, loots: 0, rests: 0, wanders: 0, skipped: 0, byName: 0,
      spotVisits: 0, spotHits: 0, spots: (opts.spots || []).length,
      routeLength: this.patrolRoute.length, laps: 0,
      anchor: { x: anchor.x, y: anchor.y }, startedAt: t0, sells: 0
    };
    const cc0 = this.combatCfg && this.combatCfg();
    const scan = true;                                    // на бегу между точками жмём Tab (стрелок — цель в дальности выстрела; ближник — моб ближе ~28 ярдов)
    const nearOk = s => !!(cc0 && cc0.ranged) || !!(s.tDist & 1);
    // вложенный запуск из hunt (_sub): гасим только гринд, а не всю задачу
    const finish = () => { if (opts && opts._sub) { this.grinding = false; this.release(); } else this.stop(); };
    // шаг в случайную точку внутри радиуса: после неудачного подхода не стоим на месте и не выбираем ту же цель
    const wanderStep = async () => {
      const a = Math.random() * 2 * Math.PI, r = cfg.radius * (0.4 + 0.6 * Math.random());
      await this.travelTo(anchor.x + r * Math.cos(a), anchor.y + r * Math.sin(a), { scan }, cfg);
    };
    const out = r => { st.laps = this.patrolLaps || 0; finish(); return { result: r, stats: st, ...this.state() }; };
    // смерть: гринд завершается со статусом задачи 'dead'; воскрешение — отдельная задача (revive)
    const dieOut = () => {
      const s = this.state(), la = this.lastAlive || {};
      const death = { x: s.x, y: s.y, zone: s.zone, mobsInFight: this.fightMobs || 0,
                      hpPctBefore: la.hpPct, manaPctBefore: la.manaPct, pet: la.pet || null, kills: st.kills };
      console.warn('ПЕРСОНАЖ ПОГИБ', death);
      this.warnings.push('персонаж погиб: ' + JSON.stringify(death));
      st.laps = this.patrolLaps || 0;
      finish();
      return { result: 'персонаж погиб', status: 'dead', death, stats: st, ...s };
    };
    // смерть: считаем, смотрим защиту от цикла смертей, воскрешаемся и продолжаем с той же точки.
    // null — продолжать гринд; иначе готовый результат задачи.
    const deathLog = st.deathLog = [];
    const onDeath = async () => {
      const s0 = this.state(), la = this.lastAlive || {};
      const rec = { x: s0.x, y: s0.y, zone: s0.zone, at: Date.now(), mobsInFight: this.fightMobs || 0,
                    hpPctBefore: la.hpPct, manaPctBefore: la.manaPct };
      deathLog.push(rec);
      st.deaths = deathLog.length;
      console.warn('ПЕРСОНАЖ ПОГИБ', rec);
      this.warnings.push('персонаж погиб: ' + JSON.stringify(rec));
      if (cfg.autoRevive === false) return dieOut();
      const recent = deathLog.filter(d => Date.now() - d.at < 600000);
      if (recent.length >= 3) {                        // защита от цикла смертей: 3 за 10 минут
        st.laps = this.patrolLaps || 0; finish();
        return { result: 'слишком много смертей', status: 'stopped', deaths: recent, stats: st, ...this.state() };
      }
      const r = await this.revive();
      if (r.result !== 'воскрешён') return out('воскрешение: ' + r.result);
      if (this.grinding) this.running = false;         // дальше работает сам цикл гринда
      return null;
    };

    if (mode === 'patrol' && !this.patrolRoute.length) {
      return out('patrol: нет мест в пределах maxDistance');
    }

    let lastProgressAt = Date.now(), lastKills = 0;
    const progress = () => { lastProgressAt = Date.now(); };
    // Данные Lua-цепочки (уровень игрока, зона, мана, дистанция до цели) не приходят: бот видит ману 0, «слепо» пьёт
    // и не баффает. Живой персонаж не бывает уровня 0 — это признак. Не стартуем и останавливаемся с понятной причиной.
    const BROKEN = 'данные из игры неполные (уровень игрока/зона/мана не читаются): перезагрузи страницу игры (F5) и скажи «перезагрузи бота»';
    const luaBroken = s => s.hp > 0 && s.pLvl === 0;
    let brokenSince = 0;
    if (luaBroken(this.state())) { await sleep(1500); if (luaBroken(this.state())) return out(BROKEN); }
    while (this.grinding) {
      const s = this.state();
      if (luaBroken(s)) { if (!brokenSince) brokenSince = Date.now(); else if (Date.now() - brokenSince > 3000) return out(BROKEN); }
      else brokenSince = 0;
      if (st.kills !== lastKills) { lastKills = st.kills; progress(); }
      if (cfg.emptyAfterMs && Date.now() - lastProgressAt > cfg.emptyAfterMs) return out('место пустое');
      if (this.dead() || s.hp === 0) {
        const d = await onDeath(); if (d) return d;
        continue;
      }
      this.lastAlive = { hpPct: +(s.hp / s.hpMax).toFixed(2), manaPct: s.manaPct, pet: s.pet };
      if (cfg.maxKills && st.kills >= cfg.maxKills) return out('лимит убийств достигнут');
      if (cfg.maxMinutes && Date.now() - t0 > cfg.maxMinutes * 60000) return out('время вышло');   // 0 — без лимита времени

      if (s.fight) {                                        // на нас напали — сначала отбиться
        const p = await this.fightAndLoot(st); if (p) return out(p); continue;
      }

      // проверка сумок: без лута они не наполняются — к торговцу не бегаем (иначе бот «убегает» со старта)
      const b = cfg.loot && this.bags ? this.bags() : null;
      if (b && b.free <= (cfg.minFreeSlots ?? 2)) {
        if (cfg.vendor) {
          const sr = await this.sellAt(cfg.vendor);
          if (sr && sr.result === 'продано') st.sells = (st.sells || 0) + 1;
          else return out('продажа: ' + (sr && sr.result ? sr.result : 'ошибка'));
          if (Math.hypot(this.state().x - anchor.x, this.state().y - anchor.y) > 0.6) await this.mountUp();   // обратно верхом
          const back = await this.moveTo(anchor.x, anchor.y);
          await this.dismount();
          if (back.result === 'бой') continue;
          if (back.result === 'застрял') return out('застрял по пути к центру после продажи');
          continue;
        } else {
          return out('сумки полные — торговец не задан');
        }
      }

      const petRest = this.petNeedsRest(cfg);
      if (s.hp / s.hpMax < cfg.restBelow || s.manaPct < cfg.manaBelow || petRest) {
        if (petRest) this.lastPetRestAt = Date.now();
        await this.rest({ manaBelow: cfg.manaBelow, healKey: cfg.healKey, healSpell: cfg.healSpell, drinkKey: cfg.drinkKey });
        st.rests++; continue;
      }
      // баффы профиля (паладин): держим всегда, вне боя
      if (cc0 && cc0.buffs) await this.ensureBuffs(cc0);
      // стрелок: боеприпасы и питомец (пауза, если питомец мёртв)
      if (cc0 && cc0.ranged) {
        const ammo = (this.deathInfo() || {}).ammo;
        if (ammo === 0) return out('кончились боеприпасы');
        if (ammo > 0 && ammo < (cc0.minAmmo || 200) && !st.ammoWarned) {
          st.ammoWarned = true;
          this.warnings.push('боеприпасов мало: ' + ammo);
        }
        const pr = await this.ensurePet(cc0);
        if (pr === 'dead') {
          st.laps = this.patrolLaps || 0; finish();
          return { result: 'питомец мёртв', status: 'paused', stats: st, ...this.state() };
        }
        if (pr === 'none') {
          if ((st.petCallFails = (st.petCallFails || 0) + 1) >= 3) return out('не удалось вызвать питомца');
          continue;
        }
        st.petCallFails = 0;
      }

      // ушли далеко — назад к центру: в radius это 1.5 радиуса охоты, в patrol — maxDistance
      const farLimit = mode === 'patrol' ? cfg.maxDistance : cfg.radius * 1.5;
      if (Math.hypot(s.x - anchor.x, s.y - anchor.y) > farLimit) {
        // подходящий враг ближе ~10 м — бьём, а не пропускаем из-за радиуса гринда
        if (await this.nearbyHostile()) {
          if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); continue; }
          const p = await this.fightAndLoot(st); if (p) return out(p);
          continue;
        }
        this.noteReject('вне радиуса');
        const m = await this.travelTo(anchor.x, anchor.y, undefined, cfg);
        if (m.result === 'застрял') return out('застрял по пути к центру');
        continue;
      }

      // на бегу уже выбрали подходящую цель (moveTo вернул 'цель') — начать бой отсюда, издалека
      if (scan && nearOk(this.state()) && this.goodPullTarget(this.state(), { needRange: true })) {
        progress();
        if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); await wanderStep(); continue; }
        const p = await this.fightAndLoot(st); if (p) return out(p);
        continue;
      }

      if (mode === 'patrol') {
        // обход по кольцу: следующее место → на месте полный осмотр → бой/лут или empty++
        const spot = this.nextPointPatrol(s, anchor, cfg);
        st.laps = this.patrolLaps || 0;
        if (!spot) { st.wanders++; await sleep(500); continue; }
        st.spotVisits++;
        const m = await this.travelTo(spot.x, spot.y, { tol: 0.4, scan }, cfg);
        if (m.result === 'бой' || m.result === 'цель') continue;   // напали по дороге / нашли цель на бегу — цикл сам начнёт бой
        this.markVisited(spot);
        const t2 = await this.findTarget({ once: true });    // на месте — быстрый осмотр: один разворот
        if (t2) {
          progress();
          st.spotHits++;
          if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); }
          else { const p = await this.fightAndLoot(st); if (p) return out(p); }
        } else {
          spot.empty++;
        }
        st.laps = this.patrolLaps || 0;
        continue;
      }

      const hasSpots = (this.grindSpots || []).length > 0;
      const tgt = await this.findTarget({ quick: hasSpots });
      if (!this.grinding) break;
      if (tgt) {
        progress();
        if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); await wanderStep(); continue; }
        const p = await this.fightAndLoot(st); if (p) return out(p);
        continue;
      }

      if (hasSpots) {
        const spot = this.nextPointRadius(s, anchor, cfg);
        if (spot) {
          st.spotVisits++;
          const m = await this.travelTo(spot.x, spot.y, { tol: 0.4, scan }, cfg);
          if (m.result === 'бой' || m.result === 'цель') continue; // напали по дороге / нашли цель на бегу — цикл сам начнёт бой
          this.markVisited(spot);
          const t2 = await this.findTarget({ quick: false }); // на месте — полный осмотр
          if (t2) {
            progress();
            st.spotHits++;
            if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); }
            else { const p = await this.fightAndLoot(st); if (p) return out(p); }
          } else {
            spot.empty++;
          }
          continue;
        }
      }

      st.wanders++;                                         // никого — побродить внутри радиуса
      if (st.wanders > 15 && st.kills === 0) return out('мобов поблизости не нашёл');
      await wanderStep();
    }
    return out('остановлен вручную');
  },
});
