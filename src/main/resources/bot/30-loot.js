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
    await this.closeInOnCorpse();
    const b0 = this.bags();
    const lo0 = (this.deathInfo() || {}).lootOpened;      // счётчик LOOT_OPENED до клика
    const pts = [];
    for (let fy = 0.45; fy <= 0.90; fy += 0.05)
      for (let fx = 0.25; fx <= 0.75; fx += 0.04) pts.push([fx, fy]);
    pts.sort((a, b) => Math.hypot(a[0] - 0.5, a[1] - 0.7) - Math.hypot(b[0] - 0.5, b[1] - 0.7));

    let scanned = 0, sawDead = false;
    for (let pass = 0; pass < 2; pass++) {       // одна повторная попытка скана
      for (const [fx, fy] of pts) {
        if (!this.running && this.job.status !== 'running') break;
        this.mouse(fx, fy, 'mousemove');
        await sleep(70); scanned++;
        if (this.state().moDead) {
          sawDead = true;
          this.mouse(fx, fy, 'mousedown', 2); await sleep(60); this.mouse(fx, fy, 'mouseup', 2);
          const t0 = Date.now();
          let sawWindow = false;
          while (Date.now() - t0 < timeout) {          // ждём: подбежал, окно открылось и закрылось
            await sleep(400);
            const s = this.state();
            if (s.fight) return { result: 'бой во время сбора', ...s };
            if (s.lootN > 0) sawWindow = true;
            if (Date.now() - t0 > 1500 && s.lootN === 0) {
              // автолут забирает добычу мгновенно и окно можно не успеть увидеть — смотрим и на сумки/деньги
              const b1 = this.bags();
              const lo1 = (this.deathInfo() || {}).lootOpened;
              const opened = lo0 != null && lo1 != null && lo1 > lo0;   // окно добычи открывалось (даже мгновенный автолут)
              const gained = opened || sawWindow || b1.free < b0.free || (b1.money || 0) > (b0.money || 0);
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
    const info = { scanned, moDeadSeen: sawDead, targetDead: !!s.target && s.targetHp === 0, tDist: s.tDist, lootN: s.lootN };
    console.warn('лут: труп не найден', info);
    this.warnings.push('лут: труп не найден ' + JSON.stringify(info));
    if (this.warnings.length > 20) this.warnings.shift();
    return { result: 'труп не найден под курсором', loot: info, ...s };
  }
});
