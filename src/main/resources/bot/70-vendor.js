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

  // Верхом (профиль с mountKey, паладин): сесть, если далеко и вне боя. Нет данных о состоянии (старый Lua) — не жмём:
  // заклинание коня переключает, и мы бы слезли. Слезать безопасно: /dismount не переключатель.
  async mountUp() {
    const cc = this.combatCfg && this.combatCfg();
    if (!cc || !cc.mountKey || this.qaddr == null) return false;
    const s = this.state();
    if (this.mounted() || s.fight || s.dead || s.ghost || this.casting()) return this.mounted();
    await this.tap(cc.mountKey);
    await sleep(600);
    await this.waitFor(() => !this.casting(), 6000, 250, () => !this.running && this.job.status !== 'running');
    await sleep(500);
    if (!this.mounted()) { this.warnings.push('не удалось сесть на коня'); if (this.warnings.length > 20) this.warnings.shift(); }
    return this.mounted();
  },
  async dismount() {
    if (!this.mounted()) return;
    await chat('/dismount');
    await sleep(400);
  },

  async _moveToVendorPoint(vendor, { tol = 0.07, maxLoops = 12 } = {}) {
    const fightBack = async () => {
      const f = await this.fight();
      if (f.result !== 'бой окончен') {
        return { ok: false, result: f, reason: 'бой: ' + f.result, dist: this._vendorDist(vendor) };
      }
      if (this.grindCfg && this.grindCfg.loot) await this.loot();
      return null;
    };
    for (let i = 0; i < maxLoops; i++) {
      // 1) грубо: moveTo с малым tol кружит вокруг точки, поэтому сначала допуск 0.3 (~15 м)
      if (i === 0 && this._vendorDist(vendor) > 0.6) await this.mountUp();     // далеко (~30 м+) — верхом
      const r = await this.moveTo(vendor.x, vendor.y, { tol: 0.3, timeout: 90000 });
      if (r.result === 'бой') {
        await this.dismount();
        const bad = await fightBack();
        if (bad) return bad;
        continue;
      }
      if (r.result !== 'дошёл' && r.result !== 'таймаут') {
        return { ok: false, result: r, reason: r.result, dist: this._vendorDist(vendor) };
      }
      await this.dismount();                     // точный подход (короткие шаги) — пешком
      // 2) точно: импульсы W с доворотом (окно торговца открывается только вплотную, ~5 м)
      const n = await this._stepToVendor(vendor, { tol, tries: 12 });
      if (n.ok) return { ok: true, result: n, dist: n.dist };
      if (n.reason === 'бой') {
        const bad = await fightBack();
        if (bad) return bad;
        continue;
      }
      // не получилось вплотную, но рядом — пусть sellAt попробует открыть окно с шагами вперёд
      if (n.dist < 0.25) return { ok: true, result: n, dist: n.dist, approx: true };
      return { ok: false, reason: 'не удалось подойти вплотную к торговцу', dist: n.dist };
    }
    return { ok: false, reason: 'не удалось дойти до торговца', dist: this._vendorDist(vendor) };
  },

  // Маленький шаг вперёд в сторону торговца. Сохранённая точка — место, где стоял игрок; NPC впереди него
  // по направлению facing. Поэтому идём ВСЕГДА по facing, а не «на точку»: стоит пройти точку — и направление
  // на неё указывает назад (бот пятился от торговца). Без facing (старые записи) идём на точку, если она дальше 0.08.
  async _creepForward(vendor, ms) {
    const s = this.state();
    if (typeof vendor.facing === 'number' && isFinite(vendor.facing)) {
      await this._faceTo(vendor.facing, { tol: 0.2, timeout: 1500 });
    } else {
      const dx = vendor.x - s.x, dy = vendor.y - s.y;
      if (Math.hypot(dx, dy) > 0.08) await this._faceTo(Math.atan2(-dx, -dy), { tol: 0.2, timeout: 1500 });
    }
    this.key('W', true);
    try {
      await sleep(ms);
    } finally {
      this.key('W', false);
    }
    await sleep(150);
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
    const mv = await this._moveToVendorPoint(vendor, { tol: 0.07 });
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

    // 4) найти торговца на экране → правый клик. Не открылось — маленький шаг вперёд по facing и снова
    //    (до 8 шагов ≈ 10 м): окно открывается только вплотную. Не видно на экране — поворот на 90° (до 4 раз).
    let opened = false, creeps = 0;
    for (let attempt = 0; attempt < 4 && !opened; attempt++) {
      if (!this.state().target && !(await targetVendor())) {
        return { result: 'торговец не найден по имени', vendor: vendor.name,
                 dist: this._vendorDist(vendor), ...this.state() };
      }
      let flagSet = false;
      for (let k = 0; k < 8 && !opened; k++) {
        if (k > 0) { await this._creepForward(vendor, 220); creeps++; }
        const f = await this._findVendorOnScreen(k === 0 ? 9000 : 2500);   // после шага экранная точка сместилась
        if (this.state().fight) break;
        if (!f.found) { if (k === 0) break; continue; }
        if (!flagSet) {
          // окно закроет Lua после продажи и ремонта (только окно, открытое ботом)
          await chat('/run BOTSELL=1');
          flagSet = true;
        }
        this.mouse(f.point[0], f.point[1], 'mousedown', 2);
        await sleep(60);
        this.mouse(f.point[0], f.point[1], 'mouseup', 2);
        const t0 = Date.now();
        while (Date.now() - t0 < 2000) {                // ждём торговца или беседу, которая станет окном (повторный клик переоткрыл бы окно)
          await sleep(200);
          if (this.bags().merchantOpen === 1) { opened = true; break; }
          if (this.state().fight) break;
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
      return { result: 'окно торговца не открылось', dist: this._vendorDist(vendor), creeps, ...this.state() };
    }

    // 5) wait until sold and closed
    const t1 = Date.now();
    while (Date.now() - t1 < 30000) {
      await sleep(400);
      const b = this.bags();
      if (b.merchantOpen === 0 && b.toSell === 0) break;
      if (b.toSell === 0 && Date.now() - t1 > 2500) break;   // всё продано, а окно Lua не закрыл — закроем ниже
      if (this.state().fight) {
        const fr = await this.fight();
        if (fr.result !== 'бой окончен') return { result: 'бой: ' + fr.result, ...fr };
      }
    }

    // окно могло остаться открытым (Lua не закрыл, или лишний клик переоткрыл) — закрываем сами
    if (this.bags().merchantOpen === 1) {
      try { await chat('/run CloseMerchant()'); } catch (e) { /* чат недоступен — не критично */ }
      await sleep(300);
    }

    // 6) результат — по фактической продаже (счётчик I[8] и деньги), а не по скану
    const after = this.bags();
    const soldDelta = Math.max(0, (after.sold || 0) - soldBefore);
    const moneyDelta = (after.money || 0) - moneyBefore;
    const soldOk = soldDelta > 0 || moneyDelta > 0 || (before.toSell === 0 && after.toSell === 0);
    this.events.push({ type: 'sell', source: 'bot', vendor: vendor.name, sold: soldDelta, moneyDelta, freeAfter: after.free,
                       ok: soldOk, zone: this.state().zone, at: Date.now() });
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