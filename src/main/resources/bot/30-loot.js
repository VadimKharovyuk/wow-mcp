// 30-loot.js — сбор добычи: ищем труп синтетической мышью, Lua сообщает moDead.
Object.assign(bot, {
  async loot({ timeout = 8000 } = {}) {
    const c = CANVAS(), rect = () => c.getBoundingClientRect();
    const mouse = (fx, fy, type, button = 0) => {
      const r = rect();
      c.dispatchEvent(new MouseEvent(type, {
        clientX: r.left + fx * r.width, clientY: r.top + fy * r.height,
        button, buttons: type === 'mousedown' ? (button === 2 ? 2 : 1) : 0, bubbles: true
      }));
    };
    const pts = [];
    for (let fy = 0.45; fy <= 0.90; fy += 0.05)
      for (let fx = 0.25; fx <= 0.75; fx += 0.04) pts.push([fx, fy]);
    pts.sort((a, b) => Math.hypot(a[0] - 0.5, a[1] - 0.7) - Math.hypot(b[0] - 0.5, b[1] - 0.7));

    for (const [fx, fy] of pts) {
      if (!this.running && this.job.status !== 'running') break;
      mouse(fx, fy, 'mousemove');
      await sleep(70);
      if (this.state().moDead) {
        mouse(fx, fy, 'mousedown', 2); await sleep(60); mouse(fx, fy, 'mouseup', 2);
        const t0 = Date.now();
        while (Date.now() - t0 < timeout) {          // ждём: подбежал, окно открылось и закрылось
          await sleep(400);
          const s = this.state();
          if (s.fight) return { result: 'бой во время сбора', ...s };
          if (Date.now() - t0 > 1500 && s.lootN === 0) return { result: 'добыча собрана', point: [fx, fy], ...s };
        }
        const s = this.state();
        return { result: s.lootN > 0 ? 'окно добычи не закрылось (сумки полные?)' : 'не удалось открыть труп', ...s };
      }
    }
    return { result: 'труп не найден под курсором', ...this.state() };
  }
});
