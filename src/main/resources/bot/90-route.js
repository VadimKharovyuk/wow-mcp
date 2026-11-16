// 90-route.js — прохождение маршрута по точкам. Точки готовит Java: записанный маршрут (задание 11) или
// путь по графу дорог (задание 10, opts.roads) — тогда точки с node=true — узлы графа.
Object.assign(bot, {
  routeProgress: null,    // { name, i, n } — «точка i из n», показывается в get_state (поле route)

  // событие обучения графа: ok / fail — проход ребра между узлами; learn — прямой участок до/от дороги ≤ 6 ед.
  roadEvent(kind, zone, a, b) {
    this.events.push({ type: 'road', kind, zone, ax: a.x, ay: a.y, bx: b.x, by: b.y, at: Date.now() });
  },
  roadLeg(zone, a, b, ok) {
    if (zone == null) return;
    if (a.node && b.node) { this.roadEvent(ok ? 'ok' : 'fail', zone, a, b); return; }
    if (ok && (a.node !== b.node) && Math.hypot(a.x - b.x, a.y - b.y) <= 6) this.roadEvent('learn', zone, a, b);
  },

  // Дорога из списка: к началу (верхом, если далеко), потом по точкам маршрута; в конце слезть с коня.
  async goRoad(points, opts = {}) {
    const s = this.state(), f = points[0];
    if (f && Math.hypot(s.x - f.x, s.y - f.y) > 0.6) await this.mountUp();
    const r = await this.goRoute(points, opts);
    await this.dismount();
    return r;
  },

  // points: [{x, y, node?}] по порядку. opts: fight (отбиваться, иначе стоп при нападении), loot (после боя),
  // tolMid (допуск на промежуточных точках), roads/zone (учим граф). Застрял на точке — пропускаем;
  // две подряд — стоп с причиной и координатами. Застрял на ребре дороги — fails++ и прямо к цели.
  async goRoute(points, { name = '', fight = true, loot = true, tolMid = 0.6, zone = null, roads = false } = {}) {
    const n = points.length;
    this.routeProgress = { name, i: 0, n };
    const s0 = this.state();
    let prev = { x: s0.x, y: s0.y, node: false };
    let skipped = 0, skippedTotal = 0, fights = 0;
    const end = (result, extra = {}) => { this.release(); this.routeProgress = null; return { result, route: name, points: n, skipped: skippedTotal, ...extra, ...this.state() }; };
    for (let i = 0; i < n; i++) {
      const p = points[i];
      this.routeProgress.i = i + 1;
      const last = i === n - 1;
      const r = await this.moveTo(p.x, p.y, { tol: last ? (roads ? 0.3 : 0.4) : tolMid, timeout: 60000 });
      if (r.result === 'дошёл') {
        skipped = 0;
        if (roads) this.roadLeg(zone, prev, p, true);
        prev = p;
        continue;
      }
      if (r.result === 'бой') {
        if (!fight) return end('бой');
        if (++fights > 30) return end('слишком много боёв по пути');
        const f = await this.fight();
        if (f.result !== 'бой окончен') return end(f.result);
        if (loot) {
          const l = await this.loot();
          if (l.result === 'бой во время сбора') {
            const f2 = await this.fight();
            if (f2.result !== 'бой окончен') return end(f2.result);
          }
        }
        i--;                                   // вернуться к той же точке
        continue;
      }
      if (r.result === 'застрял' || r.result === 'таймаут') {
        skipped++; skippedTotal++;
        if (roads) {
          this.roadLeg(zone, prev, p, false);
          this.warnings.push('застрял на дороге (' + p.x.toFixed(1) + ', ' + p.y.toFixed(1) + ')');
          if (this.warnings.length > 20) this.warnings.shift();
          if (!last && prev.node && p.node) { i = n - 2; continue; }   // ребро плохое — прямо к цели
        }
        if (skipped >= 2) return end('застрял на двух точках подряд', { at: { x: r.x, y: r.y }, point: i + 1 });
        continue;                              // пропустить точку, взять следующую
      }
      return end(r.result, { point: i + 1 });  // остановлен вручную, нет координат и т.п.
    }
    return end('маршрут пройден');
  },
});
