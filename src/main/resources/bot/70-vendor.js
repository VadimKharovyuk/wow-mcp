// 70-vendor.js - sell junk and repair at vendor
Object.assign(bot, {
  _vendorDist(vendor) {
    if (!vendor || vendor.x == null || vendor.y == null) return Infinity;
    const s = this.state();
    return Math.hypot(vendor.x - s.x, vendor.y - s.y);
  },

  // доводка вплотную: прицелиться в точку и шагнуть W на 150–250 мс, до 6 попыток
  // (moveTo с малым tol кружится вокруг точки, поэтому точность добираем импульсами)
  async _stepToVendor(vendor, { tol = 0.12, tries = 6 } = {}) {
    let dist = this._vendorDist(vendor);
    for (let i = 0; i < tries && dist > tol; i++) {
      if (this.state().fight) return { ok: false, dist, reason: 'бой' };
      const s = this.state();
      const dx = vendor.x - s.x, dy = vendor.y - s.y;
      if (Math.hypot(dx, dy) <= tol) break;
      // в moveTo: err = atan2(-dx, -dy) - facing → смотреть надо на atan2(-dx, -dy)
      await this._faceTo(Math.atan2(-dx, -dy), { tol: 0.15, timeout: 1500 });
      this.key('W', true);
      try {
        await sleep(150 + Math.round(Math.random() * 100)); // 150–250 мс
      } finally {
        this.key('W', false);
      }
      await sleep(150);
      dist = this._vendorDist(vendor);
    }
    return { ok: dist <= tol, dist };
  },

  async _moveToVendorPoint(vendor, { tol = 0.12, maxLoops = 20 } = {}) {
    const fightBack = async () => {
      const f = await this.fight();
      if (f.result !== 'бой окончен') {
        return { ok: false, result: f, reason: 'бой: ' + f.result, dist: this._vendorDist(vendor) };
      }
      if (this.grindCfg && this.grindCfg.loot) await this.loot();
      return null;
    };
    for (let i = 0; i < maxLoops; i++) {
      // точная попытка; время ограничено, иначе moveTo кружит вокруг точки
      const r = await this.moveTo(vendor.x, vendor.y, { tol, timeout: 30000 });
      if (r.result === 'дошёл') return { ok: true, result: r, dist: this._vendorDist(vendor) };
      if (r.result === 'бой') {
        const bad = await fightBack();
        if (bad) return bad;
        continue;
      }
      if (r.result === 'таймаут' || r.result === 'застрял') {
        // попались грубо (tol 0.3) и доводим импульсами до tol
        const g = await this.moveTo(vendor.x, vendor.y, { tol: 0.3, timeout: 45000 });
        if (g.result === 'бой') {
          const bad = await fightBack();
          if (bad) return bad;
          continue;
        }
        const n = await this._stepToVendor(vendor, { tol });
        if (n.ok) return { ok: true, result: n, dist: n.dist };
        if (n.reason === 'бой') continue;
        return { ok: false, reason: 'не удалось подойти вплотную к торговцу', dist: n.dist };
      }
      return { ok: false, result: r, reason: r.result, dist: this._vendorDist(vendor) };
    }
    return { ok: false, reason: 'не удалось дойти до торговца', dist: this._vendorDist(vendor) };
  },

  _normAngle(a) { return Math.atan2(Math.sin(a), Math.cos(a)); },

  // повернуть персонажа на абсолютный угол target (A — влево, D — вправо),
  // пока |ошибка| не станет меньше tol
  async _faceTo(target, { tol = 0.1, timeout = 4000 } = {}) {
    const t0 = Date.now();
    let prev = this._normAngle(target - this.state().facing), stalled = 0;
    while (Date.now() - t0 < timeout) {
      const err = this._normAngle(target - this.state().facing);
      if (Math.abs(err) < tol) { this.key('A', false); this.key('D', false); return true; }
      this.key('A', err > 0);
      this.key('D', err < 0);
      // угол не меняется (клавиша не сработала) — не крутимся до таймаута
      if (Math.abs(this._normAngle(err - prev)) < 0.005) stalled++; else stalled = 0;
      prev = err;
      if (stalled > 40) break;
      await sleep(50);
    }
    this.key('A', false); this.key('D', false);
    return Math.abs(this._normAngle(target - this.state().facing)) < Math.max(tol, 0.2);
  },

  async _rotateBy(delta, { timeout = 4000 } = {}) {
    return this._faceTo(this.state().facing + delta, { tol: 0.1, timeout });
  },

  // точки скана всего экрана; NPC рядом и крупный — начинаем с центра-низа
  _vendorScanPoints() {
    const pts = [];
    for (let fy = 0.20; fy <= 0.95; fy += 0.06)
      for (let fx = 0.05; fx <= 0.95; fx += 0.06) pts.push([fx, fy]);
    const d = ([x, y]) => Math.hypot(x - 0.5, (y - 0.82) * 0.7);
    pts.sort((a, b) => d(a) - d(b));
    return pts;
  },

  async _findVendorOnScreen(timeoutMs = 9000) {
    const pts = this._vendorScanPoints();
    const t0 = Date.now();
    for (const [fx, fy] of pts) {
      if (Date.now() - t0 > timeoutMs) break;
      this.mouse(fx, fy, 'mousemove');
      await sleep(40);
      if (this.bags().moIsTarget === 1) return { found: true, point: [fx, fy] };
      if (this.state().fight) return { found: false, reason: 'бой' };
    }
    return { found: false, reason: 'торговец не найден на экране' };
  },

  async sellAt(vendor) {
    if (!vendor || vendor.x == null || vendor.y == null || !vendor.name) {
      return { result: 'ошибка: торговец не задан', ...this.state() };
    }
    const before = this.bags();
    const moneyBefore = before.money || 0;
    const soldBefore = before.sold || 0;

    // 1) подойти вплотную к сохранённой точке (окно торговца открывается только вблизи)
    const mv = await this._moveToVendorPoint(vendor, { tol: 0.12 });
    if (!mv.ok) {
      return { result: mv.reason || 'не удалось подойти к торговцу', detail: mv.result,
               dist: mv.dist, ...this.state() };
    }

    // 2) повернуться туда, куда смотрел игрок при запоминании торговца.
    //    В старых записях facing нет — тогда сразу скан с поворотами.
    if (typeof vendor.facing === 'number' && isFinite(vendor.facing)) {
      await this._faceTo(vendor.facing);
    }

    // 3) target vendor by name
    const targetVendor = async () => {
      await chat('/target ' + vendor.name);
      for (let i = 0; i < 20; i++) {
        await sleep(100);
        if (this.state().target) return true;
      }
      return false;
    };
    if (!(await targetVendor())) {
      return { result: 'торговец не найден по имени', vendor: vendor.name,
               dist: this._vendorDist(vendor), ...this.state() };
    }

    // 4) скан всего экрана → правый клик; не нашли — поворот на 90° и повтор (4 раза)
    let opened = false;
    for (let attempt = 0; attempt < 4 && !opened; attempt++) {
      if (!this.state().target && !(await targetVendor())) {
        return { result: 'торговец не найден по имени', vendor: vendor.name,
                 dist: this._vendorDist(vendor), ...this.state() };
      }
      const f = await this._findVendorOnScreen(9000);
      if (f.found) {
        // окно закроет Lua после продажи и ремонта (только окно, открытое ботом)
        await chat('/run BOTSELL=1');
        // окно открывается только вплотную: нет за 2 с — шаг вперёд и клик снова (до 3 раз)
        for (let click = 0; click < 3 && !opened; click++) {
          if (click > 0) {
            const s = this.state();
            const dx = vendor.x - s.x, dy = vendor.y - s.y;
            await this._faceTo(Math.atan2(-dx, -dy), { tol: 0.2, timeout: 1500 });
            this.key('W', true);
            try {
              await sleep(300);
            } finally {
              this.key('W', false);
            }
            await sleep(150);
          }
          this.mouse(f.point[0], f.point[1], 'mousedown', 2);
          await sleep(60);
          this.mouse(f.point[0], f.point[1], 'mouseup', 2);
          // wait for merchant or gossip to turn into merchant
          const t0 = Date.now();
          while (Date.now() - t0 < 2000) {
            await sleep(200);
            if (this.bags().merchantOpen === 1) { opened = true; break; }
            if (this.state().fight) break;
          }
        }
      }
      if (this.state().fight) {
        const fr = await this.fight();
        if (fr.result !== 'бой окончен') return { result: 'бой: ' + fr.result, ...fr };
      }
      if (!opened && attempt < 3) {
        // торговец рядом, но вне кадра — поворачиваемся и сканируем снова
        await this._rotateBy(Math.PI / 2);
      }
    }
    if (!opened) {
      // не оставляем флаг: иначе Lua закроет окно, открытое потом игроком
      try { await chat('/run BOTSELL=nil'); } catch (e) { /* чат недоступен — не критично */ }
      // dist — чтобы было видно, дошёл ли бот вплотную до сохранённой точки
      return { result: 'окно торговца не открылось', dist: this._vendorDist(vendor), ...this.state() };
    }

    // 5) wait until sold and closed
    const t1 = Date.now();
    while (Date.now() - t1 < 30000) {
      await sleep(400);
      const b = this.bags();
      if (b.merchantOpen === 0 && b.toSell === 0) break;
      if (this.state().fight) {
        const fr = await this.fight();
        if (fr.result !== 'бой окончен') return { result: 'бой: ' + fr.result, ...fr };
      }
    }

    // 6) результат — по фактической продаже (счётчик I[8] и деньги), а не по скану
    const after = this.bags();
    const soldDelta = Math.max(0, (after.sold || 0) - soldBefore);
    const moneyDelta = (after.money || 0) - moneyBefore;
    const soldOk = soldDelta > 0 || moneyDelta > 0 || (before.toSell === 0 && after.toSell === 0);
    return {
      result: soldOk ? 'продано' : 'продажа не завершена',
      sold: soldDelta,
      freeAfter: after.free,
      moneyDelta,
      toSellAfter: after.toSell,
      merchantOpen: after.merchantOpen,
      ...this.state()
    };
  }
});