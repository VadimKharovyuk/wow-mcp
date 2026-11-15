// 95-hunt.js — hunt: «иди качайся» одной командой. Java выбирает и ранжирует кластеры (mob_cluster), здесь —
// обход: добежать (с боем по пути) → grind радиусом 3 → результат посещения уходит в БД событием 'hunt'.
Object.assign(bot, {
  huntStats: null,   // { kills, target, clusters, visited, empty, unreachable, current } — поле hunt в get_state

  // opts: { kills, loot, zoneId, clusters: [{cx, cy, spawns, mobs, names}], vendor }
  async hunt(opts = {}) {
    const target = opts.kills || 30, clusters = opts.clusters || [], loot = !!opts.loot;
    this.hunting = true;
    const hs = this.huntStats = { kills: 0, target, clusters: clusters.length, visited: 0, empty: 0, unreachable: 0, current: null };
    const emit = (c, o) => this.events.push({ type: 'hunt', zoneId: opts.zoneId, cx: c.cx, cy: c.cy, at: Date.now(), ...o });
    const end = (result, extra = {}) => {
      this.hunting = false; hs.current = null; this.grinding = false; this.release();
      return { result, hunt: hs, ...extra, ...this.state() };
    };

    for (const c of clusters) {
      if (!this.hunting) break;
      if (hs.kills >= target) break;
      hs.current = { cx: c.cx, cy: c.cy, mobs: c.mobs, spawns: c.spawns };

      // 1) добежать; не добежали (застрял, таймаут, слишком много боёв) — место недоступно на 60 мин
      const r = await this.go(c.cx, c.cy, { autoLoot: loot });
      if (!this.hunting) break;
      if (r.result !== 'дошёл') {
        if (r.result === 'застрял' || r.result === 'таймаут' || r.result === 'слишком много боёв по пути') {
          hs.unreachable++;
          this.warnings.push('hunt: место недоступно (' + c.cx + ', ' + c.cy + '): ' + r.result);
          if (this.warnings.length > 20) this.warnings.shift();
          emit(c, { found: 0, kills: 0, emptyMinutes: 60, note: 'недоступен' });
          continue;
        }
        return end(r.result, { at: { cx: c.cx, cy: c.cy } });   // бой проигран, остановили вручную и т.п.
      }

      // 2) охота на месте: radius 3 ед. карты; 60 с без новой цели — место пустое
      const g = await this.grind({ radius: 3, maxKills: target - hs.kills, loot, names: c.names || [],
                                   vendor: opts.vendor, emptyAfterMs: 60000, _sub: true });
      const k = (g.stats && g.stats.kills) || 0;
      hs.kills += k;
      hs.visited++;
      const isEmpty = g.result === 'место пустое';
      if (isEmpty) hs.empty++;
      emit(c, { found: k > 0 || ((g.stats && g.stats.spotHits) || 0) > 0 ? 1 : 0, kills: k, emptyMinutes: isEmpty ? 15 : 0 });
      if (g.result === 'место пустое' || g.result === 'лимит убийств достигнут' || g.result === 'время вышло') continue;
      // смерть без воскрешения, питомец мёртв, кончились боеприпасы, стоп, сумки — охоту прекращаем с причиной
      return end(g.result, g.status ? { status: g.status } : {});
    }
    if (!this.hunting) return end('остановлен вручную');
    return end(hs.kills >= target ? 'охота завершена: убито ' + hs.kills
                                  : 'места закончились: убито ' + hs.kills + ' из ' + target);
  },
});
