// 80-death.js — смерть → дух → бег к трупу → воскрешение. Всё через Lua /run, без кликов по окнам.
Object.assign(bot, {
  // Ждать условие cond() не дольше maxMs; true, если дождались. aborted() — досрочный выход (stop).
  async waitFor(cond, maxMs, step = 250, aborted = () => false) {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
      if (cond()) return true;
      if (aborted()) return false;
      await sleep(step);
    }
    return cond();
  },

  // Воскрешение: отпустить дух → добежать до трупа → дождаться задержки → RetrieveCorpse → отдых.
  // Результат: result 'воскрешён' | 'персонаж жив' | причина отказа; при отказе status 'failed'.
  async revive() {
    this.running = true;
    const t0 = Date.now();
    // moveTo/rest сбрасывают running сами, поэтому остановку отслеживаем по счётчику stop()
    const seq = this.stopSeq || 0, aborted = () => (this.stopSeq || 0) !== seq;
    const d0 = this.deathInfo();
    if (!d0) return { result: 'таблица смерти D не найдена (обнови Lua: перезапусти сервер и reload_bot)', status: 'failed', ...this.state() };
    if (!d0.dead && !d0.ghost) { this.release(); return { result: 'персонаж жив', ...this.state() }; }
    const start = this.state();
    const deathPos = { x: start.x, y: start.y, zone: start.zone };
    const fail = (why, extra = {}) => { this.release(); return { result: why, status: 'failed', deathPos, ...extra, ...this.state() }; };

    // 1) отпустить дух
    if (d0.dead && !d0.ghost) {
      await chat('/run RepopMe()');
      if (!(await this.waitFor(() => (this.deathInfo() || {}).ghost, 15000, 250, aborted))) return fail(aborted() ? 'остановлен вручную' : 'дух не отпустился за 15 с');
    }
    if (aborted()) return fail('остановлен вручную');

    // 2) где труп (карта обновляется раз в 0.5 с)
    await sleep(700);
    let info = this.deathInfo();
    if (!info || (info.corpseX === 0 && info.corpseY === 0)) return fail('труп не найден на карте');

    // 3) бежать к трупу (призрака никто не атакует; допуск ≈ 17 м, RetrieveCorpse работает в ~35 м)
    let run = null, tries = 0;
    while (!aborted() && tries++ < 4) {
      info = this.deathInfo();
      if (!info || (info.corpseX === 0 && info.corpseY === 0)) return fail('труп пропал с карты');
      run = await this.moveTo(info.corpseX, info.corpseY, { tol: 0.35, timeout: 240000 });
      if (run.result === 'дошёл') break;
      if (run.result === 'остановлен вручную') return fail('остановлен вручную');
      if (run.result === 'застрял' && tries >= 4) return fail('застрял по пути к трупу', { corpse: info });
      if (run.result.startsWith('нет координат')) return fail(run.result);
    }
    if (!run || run.result !== 'дошёл') return fail('не добежал до трупа: ' + (run && run.result), { corpse: info });
    const here = this.state();
    const distance = +Math.hypot(here.x - deathPos.x, here.y - deathPos.y).toFixed(2);

    // 4) задержка воскрешения
    if (!(await this.waitFor(() => ((this.deathInfo() || {}).recoveryDelay || 0) <= 0, 200000, 1000, aborted))) {
      return fail(aborted() ? 'остановлен вручную' : 'задержка воскрешения не истекла за 200 с', { corpse: info });
    }

    // 5) безопасность: враг у трупа — подождать до 20 с (мобы ходят), затем воскреснуть в любом случае
    const tSafe = Date.now();
    while (!aborted() && Date.now() - tSafe < 20000) {
      const scan = await this.scanScreen({ minLvlDiff: -99, maxLvlDiff: 99 });
      if (!scan.found) break;
      this.warnings.push('у трупа враг, жду');
      if (this.warnings.length > 20) this.warnings.shift();
      await chat('/cleartarget');
      await sleep(4000);
    }

    // 6) воскреснуть (две попытки)
    for (let i = 0; i < 2; i++) {
      await chat('/run RetrieveCorpse()');
      const alive = await this.waitFor(() => { const d = this.deathInfo(); return !!d && !d.dead && !d.ghost; }, 10000, 250, aborted);
      if (alive || aborted()) break;
    }
    const fin = this.deathInfo();
    if (!fin || fin.dead || fin.ghost) return fail('не удалось воскреснуть у трупа', { corpse: info });

    const seconds = Math.round((Date.now() - t0) / 1000);
    console.log('ВОСКРЕШЁН', { deathPos, seconds, distance });
    this.warnings.push('воскрешён: ' + seconds + ' с, бег ' + distance);
    if (this.warnings.length > 20) this.warnings.shift();

    // 7) после воскрешения ~50% HP/маны — отдохнуть
    const rest = await this.rest({ manaBelow: this.grindCfg ? this.grindCfg.manaBelow : 0.3 });
    return { result: 'воскрешён', deathPos, seconds, distance, rest: rest.result, ...this.state() };
  },
});
