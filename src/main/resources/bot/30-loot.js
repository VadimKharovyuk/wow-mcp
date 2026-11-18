// 30-loot.js — сбор добычи: ищем труп синтетической мышью, Lua сообщает moDead.
Object.assign(bot, {
  // Стрелок убивает издалека: до трупа бывает 20–30 ярдов, он мелкий и вне зоны скана мышью, а окно добычи
  // открывается вблизи. Цель (мёртвая) остаётся выбранной — идём вперёд, пока она не окажется ближе ~11 ярдов.
  async closeInOnCorpse(maxMs = 4000) {
    const s0 = this.state();
    if (!s0.target || s0.targetHp !== 0 || (s0.tDist & 2)) return;
    const t0 = Date.now();
    this.key('W', true);
    try {
      while (Date.now() - t0 < maxMs && this.job.status === 'running') {
        const s = this.state();
        if ((s.tDist & 2) || s.fight || !s.target) break;
        await sleep(100);
      }
    } finally {
      this.key('W', false);
    }
  },

  async loot({ timeout = 8000 } = {}) {
    // цель могла сброситься (добил питомец, Tab): возвращаем выбор на последнюю — это труп убитого
    { const s0 = this.state();
      if (!s0.target || s0.targetHp > 0) { await chat('/targetlasttarget'); await this.waitFrames(2); } }
    await this.closeInOnCorpse();
    const b0 = this.bags();
    const lo0 = (this.deathInfo() || {}).lootOpened;      // счётчик LOOT_OPENED до клика
    const pts = [];
    for (let fy = 0.45; fy <= 0.90; fy += 0.05)
      for (let fx = 0.25; fx <= 0.75; fx += 0.04) pts.push([fx, fy]);
    pts.sort((a, b) => Math.hypot(a[0] - 0.5, a[1] - 0.7) - Math.hypot(b[0] - 0.5, b[1] - 0.7));

    let scanned = 0, sawDead = false;
    const lp0 = this.state(), lt0 = Date.now();
    // кликаем только по трупу текущей цели (курсор над целью): правый клик по чужому обобранному трупу запускает
    // автоподход к нему, и бот убегает от своего. Оба прохода строгие; без проверки (старый Lua / нет мёртвой цели)
    // остаётся прежнее поведение — по любому мёртвому под курсором
    const sT = this.state(), strictOk = this.steerSupported() && sT.target && sT.targetHp === 0;
    for (let pass = 0; pass < 2; pass++) {       // одна повторная попытка скана
      const strict = strictOk;
      for (const [fx, fy] of pts) {
        if (!this.running && this.job.status !== 'running') break;
        this.mouse(fx, fy, 'mousemove');
        await sleep(70); scanned++;
        if (this.state().moDead && (!strict || this.overTarget())) {
          sawDead = true;
          this.mouse(fx, fy, 'mousedown', 2); await sleep(60); this.mouse(fx, fy, 'mouseup', 2);
          const t0 = Date.now();
          let sawWindow = false, retried = false, moved = false;
          let lastPos = { x: this.state().x, y: this.state().y }, lastMoveAt = t0;
          while (Date.now() - t0 < timeout) {          // ждём: подбежал, окно открылось и закрылось
            await sleep(400);
            const s = this.state();
            if (s.fight) return { result: 'бой во время сбора', ...s };
            if (s.lootN > 0) sawWindow = true;
            // персонаж ещё бежит к трупу (автоподход) — «пусто» объявлять рано
            if (Math.hypot(s.x - lastPos.x, s.y - lastPos.y) > 0.01) { lastPos = { x: s.x, y: s.y }; lastMoveAt = Date.now(); moved = true; }
            const settled = Date.now() - t0 > 1500 && Date.now() - lastMoveAt > 1200;
            if (settled && s.lootN === 0) {
              // автолут забирает добычу мгновенно и окно можно не успеть увидеть — смотрим и на сумки/деньги
              const b1 = this.bags();
              const lo1 = (this.deathInfo() || {}).lootOpened;
              const opened = lo0 != null && lo1 != null && lo1 > lo0;   // окно добычи открывалось (даже мгновенный автолут)
              const gained = opened || sawWindow || b1.free < b0.free || (b1.money || 0) > (b0.money || 0);
              if (!gained && !retried && (this.running || this.job.status === 'running')) {     // один повторный клик: первый мог не дойти
                retried = true;
                this.mouse(fx, fy, 'mousemove'); await sleep(70);
                this.mouse(fx, fy, 'mousedown', 2); await sleep(60); this.mouse(fx, fy, 'mouseup', 2);
                lastMoveAt = Date.now();
                continue;
              }
              if (!gained) {
                this.events.push({ type: 'lootmiss', result: 'пустой труп или не открылся', source: 'bot',
                                   zone: s.zone, x: s.x, y: s.y, tDist: s.tDist, waitedMs: Date.now() - t0,
                                   moved, retried, at: Date.now() });
              }
              this.lootInfo = { strict, pass, dx: +(s.x - lp0.x).toFixed(3), dy: +(s.y - lp0.y).toFixed(3), moved, retried, tDist: s.tDist, totalMs: Date.now() - lt0 };
              return { result: gained ? 'добыча собрана' : 'пустой труп или не открылся', point: [fx, fy], ...s };
            }
          }
          const s = this.state();
          return { result: s.lootN > 0 ? 'окно добычи не закрылось (сумки полные?)' : 'не удалось открыть труп', ...s };
        }
      }
      if (!this.running && this.job.status !== 'running') break;   // остановили — вторую попытку не делаем
      if (pass === 0) {
        await sleep(500);                       // труп мог ещё не лечь / курсор не обновился
        const f = this.state();
        if (f.fight) return { result: 'бой во время сбора', ...f };
      }
    }
    const s = this.state();
    this.lootInfo = { strict: strictOk, scanned, moDeadSeen: sawDead, dx: +(s.x - lp0.x).toFixed(3), dy: +(s.y - lp0.y).toFixed(3), tDist: s.tDist, totalMs: Date.now() - lt0 };
    const info = { scanned, moDeadSeen: sawDead, targetDead: !!s.target && s.targetHp === 0, tDist: s.tDist, lootN: s.lootN };
    console.warn('лут: труп не найден', info);
    this.events.push({ type: 'lootmiss', result: 'труп не найден', source: 'bot', zone: s.zone, x: s.x, y: s.y,
                       scanned, moDeadSeen: sawDead, tDist: s.tDist, at: Date.now() });
    this.warnings.push('лут: труп не найден ' + JSON.stringify(info));
    if (this.warnings.length > 20) this.warnings.shift();
    return { result: 'труп не найден под курсором', loot: info, ...s };
  }
});
