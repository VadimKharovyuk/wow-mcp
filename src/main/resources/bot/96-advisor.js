// 96-advisor.js — правила советника: Java раз в минуту (режим apply) присылает bot.applyDirectives([...]). Запреты и штрафы клеток учитывает
// выбор точек охоты (radius и patrol), постоянные точки упора попадают в память упоров (её читает обход упоров на бегу).
// Бот сам проверяет всё, что пришло: белый список видов, границы координат и радиуса, срок действия, не больше 200 правил.
Object.assign(bot, {
  directives: [],          // [{kind, zone, x, y, r, until}], until — миллисекунды эпохи
  directivesSig: '',

  applyDirectives(list) {
    const now = Date.now(), kinds = ['avoid_point', 'avoid_spot', 'penalize_spot'];
    const clean = [];
    for (const d of Array.isArray(list) ? list : []) {
      if (!d || kinds.indexOf(d.kind) < 0) continue;
      const zone = +d.zone, x = +d.x, y = +d.y, r = +d.r, until = +d.until;
      if (![zone, x, y, r, until].every(Number.isFinite)) continue;
      if (x < 0 || x > 100 || y < 0 || y > 100 || r <= 0 || r > 3 || until <= now) continue;
      clean.push({ kind: d.kind, zone, x, y, r, until });
      if (clean.length >= 200) break;
    }
    const sig = JSON.stringify(clean.map(d => [d.kind, d.zone, d.x, d.y, d.r]));
    const changed = sig !== this.directivesSig;
    this.directives = clean;
    this.directivesSig = sig;
    // постоянные точки упора — в память упоров (надёжные: n ≥ 2)
    try {
      const mem = this.stuckList();
      for (const d of clean) {
        if (d.kind !== 'avoid_point') continue;
        if (!mem.some(q => q.zone === d.zone && Math.hypot(q.x - d.x, q.y - d.y) < 0.06)) mem.push({ zone: d.zone, x: d.x, y: d.y, n: 3, at: now, persistent: true });
      }
    } catch (e) { /* память упоров необязательна */ }
    if (changed && this.log) {
      const by = k => clean.filter(d => d.kind === k).length;
      this.log('info', 'advisor', 'правила советника применены: ' + clean.length, { total: clean.length, points: by('avoid_point'), banned: by('avoid_spot'), penalized: by('penalize_spot') });
    }
    return clean.length;
  },

  // Как правила влияют на место охоты (то же, что DirectiveFilter в Java): 0 — запрещено, 0.5 — штраф, 1 — без изменений.
  // Точки упора (avoid_point) места охоты не меняют — их обходит бег.
  directiveFactor(zone, x, y) {
    const now = Date.now();
    let f = 1;
    for (const d of this.directives || []) {
      if (d.zone !== zone || d.until <= now || d.kind === 'avoid_point') continue;
      if (Math.hypot(d.x - x, d.y - y) > d.r) continue;
      if (d.kind === 'avoid_spot') return 0;
      f *= 0.5;
    }
    return f;
  }
});
