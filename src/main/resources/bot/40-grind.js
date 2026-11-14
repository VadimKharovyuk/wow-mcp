// 40-grind.js — цикл гринда: найти моба → подойти → бой (+ лут) → отдых → повтор.
Object.assign(bot, {
  grindCfg: {
    mode: 'radius',     // 'radius' — охота вокруг старта; 'patrol' — обход мест по кольцу
    maxDistance: 15,    // patrol: максимальное расстояние до мест маршрута (единицы карты)
    radius: 2.5,        // радиус охоты вокруг стартовой точки (единицы карты)
    minLvlDiff: -5,     // не трогать мобов слабее (серые — без опыта)
    maxLvlDiff: 2,      // и сильнее себя больше чем на 2 уровня
    restBelow: 0.6,     // отдыхать, если HP ниже 60%
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
  },
  grindSpots: [],
  grindStats: null,

  // null — цель подходит; иначе строка-причина отказа
  targetProblem(s) {
    if (!s.target || !s.enemy || s.targetHp === 0) return 'нет цели';
    if (s.tFlags & 1) return 'игрок';
    if (s.tFlags & 2) return 'элита/редкий';
    if (s.tFlags & 4) return 'чужой моб';
    if (s.tLvl > this.grindCfg.maxLvlDiff) return 'слишком сильный';
    if (s.tLvl < this.grindCfg.minLvlDiff) return 'слишком слабый';
    return null;
  },

  // Tab по кругу: 3 нажатия, поворот ~60°, и так 6 раз (полный оборот)
  async findTarget({ quick = false } = {}) {
    if (quick) {
      // быстрый режим для проверки конкретной точки: один оборот Tab, без lookAround
      for (let i = 0; i < 6 && this.grinding; i++) {
        await this.tap('TAB'); await sleep(250);
        const s = this.state();
        if (!this.targetProblem(s)) return s;
      }
      if (!this.grinding) return null;
      await chat('/cleartarget');
      return null;
    }
    for (let turn = 0; turn < 2 && this.grinding; turn++) {
      for (let i = 0; i < 3 && this.grinding; i++) {
        await this.tap('TAB'); await sleep(250);
        const s = this.state();
        if (!this.targetProblem(s)) return s;
      }
      await this.hold('A', 330); await sleep(150);
    }
    if (!this.grinding) return null;
    const r = await this.lookAround({ minLvlDiff: this.grindCfg.minLvlDiff, maxLvlDiff: this.grindCfg.maxLvlDiff });
    if (r && r.found) return this.state();
    await chat('/cleartarget');
    return null;
  },

  // подойти, пока умение агро не станет доступно (цель в его радиусе)
  async approach(maxMs = 6000) {
    const isReady = k => (this.state().ready >> (+k - 1)) & 1;
    const t0 = Date.now();
    this.key('W', true);
    while (this.grinding && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2)) break;
      await sleep(150);
    }
    this.key('W', false);
    const s = this.state();
    return s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2);
  },

  // бой (+ лут, если grindCfg.loot); возвращает null если всё ок, иначе причину для выхода из гринда
  async fightAndLoot(st) {
    const f = await this.fight();
    if (!this.grinding) return 'остановлен вручную';
    if (f.result !== 'бой окончен') return 'бой: ' + f.result;
    st.kills++;
    if (this.grindCfg.loot) {
      const l = await this.loot();
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
    this.grinding = true;
    const anchor = this.state(), t0 = Date.now();
    this.grindSpots = (opts.spots || []).map(p => ({ ...p, visitedAt: 0, empty: 0 }));
    this.patrolIdx = 0;
    this.patrolLaps = 0;
    this.patrolRoute = mode === 'patrol' ? this.buildPatrolRoute(anchor, cfg) : [];
    const st = this.grindStats = {
      mode,
      kills: 0, loots: 0, rests: 0, wanders: 0, skipped: 0,
      spotVisits: 0, spotHits: 0, spots: (opts.spots || []).length,
      routeLength: this.patrolRoute.length, laps: 0,
      anchor: { x: anchor.x, y: anchor.y }, startedAt: t0, sells: 0
    };
    const out = r => { st.laps = this.patrolLaps || 0; this.stop(); return { result: r, stats: st, ...this.state() }; };

    if (mode === 'patrol' && !this.patrolRoute.length) {
      return out('patrol: нет мест в пределах maxDistance');
    }

    while (this.grinding) {
      const s = this.state();
      if (s.hp === 0) return out('персонаж погиб');
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

      if (s.hp / s.hpMax < cfg.restBelow || s.manaPct < cfg.manaBelow) {
        await this.rest({ manaBelow: cfg.manaBelow, healKey: cfg.healKey, healSpell: cfg.healSpell, drinkKey: cfg.drinkKey });
        st.rests++; continue;
      }
      // ушли далеко — назад к центру: в radius это 1.5 радиуса охоты, в patrol — maxDistance
      const farLimit = mode === 'patrol' ? cfg.maxDistance : cfg.radius * 1.5;
      if (Math.hypot(s.x - anchor.x, s.y - anchor.y) > farLimit) {
        const m = await this.moveTo(anchor.x, anchor.y);
        if (m.result === 'застрял') return out('застрял по пути к центру');
        continue;
      }

      if (mode === 'patrol') {
        // обход по кольцу: следующее место → на месте полный осмотр → бой/лут или empty++
        const spot = this.nextPointPatrol(s, anchor, cfg);
        st.laps = this.patrolLaps || 0;
        if (!spot) { st.wanders++; await sleep(500); continue; }
        st.spotVisits++;
        const m = await this.moveTo(spot.x, spot.y, { tol: 0.4 });
        if (m.result === 'бой') continue;                   // напали по дороге — цикл сам отобьётся
        spot.visitedAt = Date.now();
        const t2 = await this.findTarget({ quick: false });  // на месте — полный осмотр
        if (t2) {
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
        if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); continue; }
        const p = await this.fightAndLoot(st); if (p) return out(p);
        continue;
      }

      if (hasSpots) {
        const spot = this.nextPointRadius(s, anchor, cfg);
        if (spot) {
          st.spotVisits++;
          const m = await this.moveTo(spot.x, spot.y, { tol: 0.4 });
          if (m.result === 'бой') continue;                 // напали по дороге — цикл сам отобьётся
          spot.visitedAt = Date.now();
          const t2 = await this.findTarget({ quick: false }); // на месте — полный осмотр
          if (t2) {
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
      const a = Math.random() * 2 * Math.PI, r = cfg.radius * (0.4 + 0.6 * Math.random());
      await this.moveTo(anchor.x + r * Math.cos(a), anchor.y + r * Math.sin(a));
    }
    return out('остановлен вручную');
  },
});
