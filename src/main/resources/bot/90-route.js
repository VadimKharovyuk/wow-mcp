// 90-route.js — прохождение записанного маршрута по точкам (точки готовит Java: прореживание, направление, старт).
Object.assign(bot, {
  routeProgress: null,    // { name, i, n } — «точка i из n», показывается в get_state (поле route)

  // points: [{x, y}, ...] по порядку прохождения. По пути отбиваемся от мобов и возвращаемся к той же точке.
  // Застрял на точке — пропускаем её; две подряд — стоп с причиной и координатами.
  async goRoute(points, { name = '' } = {}) {
    const n = points.length;
    this.routeProgress = { name, i: 0, n };
    let skipped = 0, skippedTotal = 0, fights = 0;
    const end = (result, extra = {}) => { this.release(); this.routeProgress = null; return { result, route: name, points: n, skipped: skippedTotal, ...extra, ...this.state() }; };
    for (let i = 0; i < n; i++) {
      const p = points[i];
      this.routeProgress.i = i + 1;
      const last = i === n - 1;
      const r = await this.moveTo(p.x, p.y, { tol: last ? 0.4 : 0.6, timeout: 60000 });
      if (r.result === 'дошёл') { skipped = 0; continue; }
      if (r.result === 'бой') {
        if (++fights > 30) return end('слишком много боёв по пути');
        const f = await this.fight();
        if (f.result !== 'бой окончен') return end(f.result);
        const l = await this.loot();
        if (l.result === 'бой во время сбора') {
          const f2 = await this.fight();
          if (f2.result !== 'бой окончен') return end(f2.result);
        }
        i--;                                   // вернуться к той же точке
        continue;
      }
      if (r.result === 'застрял' || r.result === 'таймаут') {
        skipped++; skippedTotal++;
        if (skipped >= 2) return end('застрял на двух точках подряд', { at: { x: r.x, y: r.y }, point: i + 1 });
        continue;                              // пропустить точку, взять следующую
      }
      return end(r.result, { point: i + 1 });  // остановлен вручную, нет координат и т.п.
    }
    return end('маршрут пройден');
  },
});
