// 50-scan.js — скан видимой области мышью: найти живых врагов во всей видимой области.
Object.assign(bot, {
  // Скан видимой области: курсор идёт по сетке от центра к краям; Lua сообщает «под курсором живой враг»
  async scanScreen({ minLvlDiff = -5, maxLvlDiff = 2 } = {}) {
    const pts = [];
    for (let fy = 0.30; fy <= 0.72; fy += 0.07)
      for (let fx = 0.06; fx <= 0.94; fx += 0.06) pts.push([fx, fy]);
    pts.sort((a, b) => (Math.abs(a[0] - 0.5) + Math.abs(a[1] - 0.5) * 0.5)
                     - (Math.abs(b[0] - 0.5) + Math.abs(b[1] - 0.5) * 0.5));
    let seen = 0;
    const rejected = {};
    const bump = (reason) => { rejected[reason] = (rejected[reason] || 0) + 1; };
    for (const [fx, fy] of pts) {
      if (this.job.status === 'running' && !this.grinding && this.job.name !== 'lookAround') break;
      this.mouse(fx, fy); await sleep(45);
      const s = this.state();
      if (!s.moAlive) continue;
      seen++;
      if (s.moLvl < minLvlDiff) { bump('слишком слабый'); continue; }
      if (s.moLvl > maxLvlDiff) { bump('слишком сильный'); continue; }
      await this.click(fx, fy, 0);             // левый клик = выделить цель
      await sleep(200);
      const t = this.state();
      const reason = this.targetProblem(t);
      if (reason) {
        bump(reason);
        // цель после клика отклонена — сбрасываем её
        await chat('/cleartarget');
        await sleep(150);
        continue;
      }
      return { found: true, point: [fx, fy], seen, rejected, ...t };
    }
    return { found: false, seen, rejected };
  },

  // Осмотреться: скан, поворот ~90°, 4 раза
  async lookAround(opts = {}) {
    let totalSeen = 0;
    const totalRejected = {};
    const merge = (r) => {
      totalSeen += (r.seen || 0);
      for (const k in (r.rejected || {})) {
        totalRejected[k] = (totalRejected[k] || 0) + (r.rejected[k] || 0);
      }
    };
    for (let i = 0; i < 4; i++) {
      const r = await this.scanScreen(opts);
      merge(r);
      if (r.found) return { result: 'цель найдена', seen: totalSeen, rejected: totalRejected, ...r };
      if (this.state().fight) return { result: 'бой', seen: totalSeen, rejected: totalRejected, ...this.state() };
      await this.hold('A', 500); await sleep(150);
    }
    return { result: 'никого не видно', seen: totalSeen, rejected: totalRejected, ...this.state() };
  },
});