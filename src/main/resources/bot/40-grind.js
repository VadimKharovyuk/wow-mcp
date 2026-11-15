// 40-grind.js — цикл гринда: найти моба → подойти → бой (+ лут) → отдых → повтор.
Object.assign(bot, {
  grindCfg: {
    mode: 'radius',     // 'radius' — охота вокруг старта; 'patrol' — обход мест по кольцу
    maxDistance: 15,    // patrol: максимальное расстояние до мест маршрута (единицы карты)
    radius: 2.5,        // радиус охоты вокруг стартовой точки (единицы карты)
    minLvlDiff: -5,     // не трогать мобов слабее (серые — без опыта)
    maxLvlDiff: 2,      // и сильнее себя больше чем на 2 уровня
    restBelow: 0.7,     // отдыхать (и не начинать новый пулл), если HP ниже 70%
    petRestBelow: 0.5,  // отдыхать (лечить питомца), если HP питомца ниже 50% и в ротации есть его лечение
    manaBelow: 0.3,    // отдыхать, если мана ниже 30%
    pullKey: '3',       // умение для агро издалека
    healKey: '7',       // лечение заклинанием в отдыхе (v1)
    healSpell: null,     // лечение заклинанием в отдыхе (v2, по хешу названия)
    drinkKey: '0',      // питьё в отдыхе
    maxKills: 10,       // 0 = без лимита
    loot: false,        // собирать добычу после боя; false — просто убивать, без лута
    maxMinutes: 30,
    respawnSec: 240,    // через сколько секунд место из базы снова считается «тёплым»
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

  // null — цель подходит; иначе причина отказа (видна в get_state → grind.rejects):
  // мёртв / нейтральный / игрок / элита/редкий / занят / уровень / не зверь (+ вне радиуса, далеко для выстрела)
  targetProblem(s) {
    if (!s.target) return 'нет цели';
    if (s.targetHp === 0) return 'мёртв';
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
    for (const nm of names) {
      if (!this.grinding || this.state().fight) return null;
      await chat('/target ' + nm);
      await sleep(300);
      await this.waitFrames(2);
      const s = this.state();
      if (this.goodPullTarget(s)) {
        // /target имя берёт моба и за 40+ ярдов, и за спиной; берём только тех, кто ближе ~28 ярдов (tDist & 1)
        if (!(s.tDist & 1)) { this.noteReject('вне дальности (>28 ярдов)'); continue; }
        if (this.grindStats) this.grindStats.byName = (this.grindStats.byName || 0) + 1;
        return s;
      }
    }
    return null;
  },

  // Tab по кругу: 3 нажатия, поворот ~60°, и так 6 раз (полный оборот)
  async findTarget({ quick = false } = {}) {
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

  // стрелку нужен питомец: нет — вызвать (клавиша petCallKey, каст ~5 с); мёртв — 'dead' (гринд встанет на паузу).
  // 'ok' — питомец есть или данных нет (старый Lua / не стрелок); 'none' — вызвать не удалось.
  async ensurePet(cc) {
    const p = this.pet();
    if (!p || !cc || !cc.petCallKey) return 'ok';
    if (p.exists && p.dead) return 'dead';
    if (p.exists || this.state().fight) return 'ok';
    await this.tap(cc.petCallKey);
    await sleep(500);
    await this.waitFor(() => !this.casting(), 8000, 250, () => !this.grinding);
    await sleep(500);
    const q = this.pet();
    return q && q.exists ? 'ok' : 'none';
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
  async approach(maxMs = 6000) {
    // стрелок: идти, пока цель не достаётся выстрелом, и сразу остановиться (вплотную не подбегать)
    const cc = this.combatCfg && this.combatCfg();
    if (cc && cc.ranged && this.spellInRange(cc.shootSpell) !== -1) {
      const t1 = Date.now();
      let best = this.proximity(), lastGain = t1;
      this.key('W', true);
      while (this.grinding && Date.now() - t1 < maxMs) {
        if (this.state().fight || this.spellInRange(cc.shootSpell) === 1) break;
        // бежим прямо; если за 3.5 с ближе не стало (цель за спиной, обрыв, препятствие) — сдаёмся
        const pr = this.proximity();
        if (pr > best) { best = pr; lastGain = Date.now(); } else if (Date.now() - lastGain > 3500) break;
        await sleep(100);
      }
      this.key('W', false);
      return this.state().fight || this.spellInRange(cc.shootSpell) === 1;
    }
    const isReady = k => (this.state().ready >> (+k - 1)) & 1;
    const t0 = Date.now();
    let best = this.proximity(), lastGain = t0;
    this.key('W', true);
    while (this.grinding && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2)) break;
      const pr = this.proximity();
      if (pr > best) { best = pr; lastGain = Date.now(); } else if (Date.now() - lastGain > 3500) break;
      await sleep(150);
    }
    this.key('W', false);
    const s = this.state();
    return s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2);
  },

  // бой (+ лут, если grindCfg.loot); возвращает null если всё ок, иначе причину для выхода из гринда
  async fightAndLoot(st) {
    if (!this.grinding) return 'остановлен вручную';   // fight() сам включает running — после stop() не начинать бой
    // fleeAt: 0 — в автономной охоте не сдаёмся при HP < 20%: именно тогда нужны аварийные правила ротации
    // (лечение, ловушка), а остановка боя с «нужен выбор» оставляла персонажа без управления и без воскрешения
    const f = await this.fight({ fleeAt: 0 });
    if (this.dead() || f.hp === 0 || f.result === 'персонаж погиб') return null;   // погибли: гринд-цикл увидит смерть на следующем шаге
    if (!this.grinding) return 'остановлен вручную';
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
      if (Math.hypot(p.x - anchor.x, p.y - anchor.y) > cfg.radius) continue;       // вне зоны охоты
      if (p.visitedAt && now - p.visitedAt < cfg.respawnSec * 1000) continue;      // ещё не респавнулось
      const dist = Math.hypot(p.x - s.x, p.y - s.y) + 0.3;                        // от текущей позиции
      const score = p.kills * Math.pow(0.5, p.empty) / dist;                    // пустые места теряют вес
      if (score > bestScore) { bestScore = score; best = p; }
    }
    return best;
  },

  // patrol: кольцевой маршрут — жадно «ближайшее следующее» от текущей позиции,
  // только места не дальше maxDistance от anchor (до 40 мест)
  buildPatrolRoute(anchor, cfg) {
    const left = (this.grindSpots || [])
        .filter(p => Math.hypot(p.x - anchor.x, p.y - anchor.y) <= cfg.maxDistance)
        .slice(0, 40);
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

  async grind(opts = {}) {
    const cfg = Object.assign(this.grindCfg, opts || {});
    const mode = (opts && opts.mode) === 'patrol' ? 'patrol' : 'radius'; // по умолчанию radius
    cfg.names = (opts && opts.names) || [];
    cfg.emptyAfterMs = (opts && opts.emptyAfterMs) || 0;
    this.grinding = true;
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
    const scan = !!(cc0 && cc0.ranged);                   // стрелок ищет цели на бегу
    // вложенный запуск из hunt (_sub): гасим только гринд, а не всю задачу
    const finish = () => { if (opts && opts._sub) { this.grinding = false; this.release(); } else this.stop(); };
    // шаг в случайную точку внутри радиуса: после неудачного подхода не стоим на месте и не выбираем ту же цель
    const wanderStep = async () => {
      const a = Math.random() * 2 * Math.PI, r = cfg.radius * (0.4 + 0.6 * Math.random());
      await this.moveTo(anchor.x + r * Math.cos(a), anchor.y + r * Math.sin(a), { scan });
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
    while (this.grinding) {
      const s = this.state();
      if (st.kills !== lastKills) { lastKills = st.kills; progress(); }
      if (cfg.emptyAfterMs && Date.now() - lastProgressAt > cfg.emptyAfterMs) return out('место пустое');
      if (this.dead() || s.hp === 0) {
        const d = await onDeath(); if (d) return d;
        continue;
      }
      this.lastAlive = { hpPct: +(s.hp / s.hpMax).toFixed(2), manaPct: s.manaPct, pet: s.pet };
      if (cfg.maxKills && st.kills >= cfg.maxKills) return out('лимит убийств достигнут');
      if (Date.now() - t0 > cfg.maxMinutes * 60000) return out('время вышло');

      if (s.fight) {                                        // на нас напали — сначала отбиться
        const p = await this.fightAndLoot(st); if (p) return out(p); continue;
      }

      // проверка сумок — работает при любом cfg.loot
      const b = this.bags ? this.bags() : null;
      if (b && b.free <= (cfg.minFreeSlots ?? 2)) {
        if (cfg.vendor) {
          const sr = await this.sellAt(cfg.vendor);
          if (sr && sr.result === 'продано') st.sells = (st.sells || 0) + 1;
          else return out('продажа: ' + (sr && sr.result ? sr.result : 'ошибка'));
          const back = await this.moveTo(anchor.x, anchor.y);
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
        const m = await this.moveTo(anchor.x, anchor.y);
        if (m.result === 'застрял') return out('застрял по пути к центру');
        continue;
      }

      // на бегу уже выбрали подходящую цель (moveTo вернул 'цель') — начать бой отсюда, издалека
      if (scan && this.goodPullTarget(this.state(), { needRange: true })) {
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
        const m = await this.moveTo(spot.x, spot.y, { tol: 0.4, scan });
        if (m.result === 'бой') continue;                   // напали по дороге — цикл сам отобьётся
        spot.visitedAt = Date.now();
        const t2 = await this.findTarget({ quick: false });  // на месте — полный осмотр
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
          const m = await this.moveTo(spot.x, spot.y, { tol: 0.4, scan });
          if (m.result === 'бой') continue;                 // напали по дороге — цикл сам отобьётся
          spot.visitedAt = Date.now();
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
