// 20-fight.js — бой по реальной готовности умений и отдых.

// Сколько не двигаться после нажатия 4: чтобы не сбить чтение лечащего заклинания.
const HEAL_CAST_LOCK_MS = 3000;

// Отдых по профилям. Профиля нет в списке — работают параметры rest() (паладин: лечение на 7, вода на 0).
// foodKey — еда (жмётся один раз), drinkKey — вода, healKey/healSpell — лечащее заклинание.
const PROFILE_REST = {
  hunter: { foodKey: '9', drinkKey: '0' },
};

Object.assign(bot, {
  // Боевые настройки профиля. ranged — стрелок: не подходить вплотную, отходить, когда моба держит питомец.
  // shootSpell — по нему проверяем «достаю выстрелом»; meleeSpell — «моб вплотную» (мёртвая зона).
  combatCfg() {
    const C = {
      hunter: { ranged: true, petCallKey: '6' /* Призыв питомца */, revivePetSpell: 1068909352 /* Воскрешение питомца (982) */, minAmmo: 200,
                // Замораживающая ловушка (аварийная): HP ниже trapBelowHpPct, моб вплотную и бьёт охотника — поставить
                // и пятиться. Пока ловушки нет в панели умений (не выучена), правило молча пропускается.
                trapSpell: 197174967 /* Замораживающая ловушка */, trapBelowHpPct: 0.7,
                // Притвориться мёртвым (с 30 ур.): HP ниже feignBelowHpPct — моб теряет цель, бой прекращается.
                // Пока умения нет в панели, правило молча пропускается.
                feignSpell: 194715437 /* Притвориться мёртвым */, feignBelowHpPct: 0.15,
                shootSpell: 1278643508 /* Чародейский выстрел */,
                meleeSpell: 1704697426 /* Удар ящера */, beastsOnly: true,
                // Закреплённая ротация: используется вместо выученной (автообучение пока учит
                // автострельбу и лечение питомца без условия). Убрать, когда обучение исправят.
                rotation: [
                  { spell: 1635767960, maxPetHpPct: 0.5 },     // Лечение питомца, если у питомца < 50%
                  { spell: 1554409780, repeatMs: 15000 },       // Укус змеи — в начале боя, потом раз в 15 с
                  { spell: 913131775 },                         // Кровавое неистовство
                  { spell: 1704697426 },                        // Удар ящера (только вплотную)
                  { spell: 1278643508 },                        // Чародейский выстрел
                ] },
      // Паладин: баффы держим всегда. Видеть ауры бот не умеет — жмём по таймеру: на старте гринда, после воскрешения
      // и раз в everyMs (лишнее нажатие просто обновляет бафф). Печать 30 мин, Праведное неистовство 30 мин, Короли 10 мин.
      paladin: { buffs: [
        { key: 'G', name: 'Печать праведности',    everyMs: 25 * 60000 },
        { key: 'T', name: 'Праведное неистовство', everyMs: 25 * 60000 },
        { key: 'Y', name: 'Благословение королей', everyMs: 9 * 60000 },
      ] },
    };
    return C[this.profile] || null;
  },
  // ротация для боя: закреплённая в профиле, иначе выученная/заданная (this.rotation)
  activeRotation() { const c = this.combatCfg(); return (c && c.rotation) || this.rotation || []; },
  spellInRange(h) { const r = (this.spells() || []).find(x => x.nameHash === h); return r ? r.inRange : -1; },

  // повернуться к направлению a (радианы, как facing) — держим A/D, пока не довернём
  // abort() — условие досрочного выхода (stop): иначе после «стоп» поворот ещё до 2.5 с жмёт A/D
  async turnTo(a, maxMs = 2500, abort = null) {
    const norm = v => Math.atan2(Math.sin(v), Math.cos(v));
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs && !(abort && abort())) {
      const e = norm(a - this.state().facing);
      if (Math.abs(e) < 0.12) break;
      this.key('A', e > 0); this.key('D', e < 0);
      await sleep(30);
    }
    this.key('A', false); this.key('D', false);
  },

  // отход стрелка: несколько шагов назад (S), не разворачиваясь — лицом к мобу, чтобы сразу стрелять.
  // ms — сколько держать S (≈ 3–4 ярда за 900 мс: пятиться медленнее, чем бежать). После stop() прерывается.
  async retreat(ms = 900) {
    this.key('W', false);
    this.key('S', true);
    try {
      const t0 = Date.now();
      while (Date.now() - t0 < ms && this.running) await sleep(50);
    } finally {
      this.key('S', false);
    }
  },

  // когда в последний раз нажимали лечение (игрока или питомца) — его чтение прерывается движением
  lastHealCastAt() {
    this.lastKey = this.lastKey || {}; this.lastSpellAt = this.lastSpellAt || {};
    let t = this.lastKey['4'] || 0;                               // v1: паладин лечит на 4
    for (const r of this.activeRotation()) {
      if (!r.maxHpPct && !r.maxPetHpPct) continue;
      const v = r.spell ? (this.lastSpellAt[r.spell] || 0) : (this.lastKey[r.key] || 0);
      if (v > t) t = v;
    }
    return t;
  },

  // почему нажатие не сработало: последняя ошибка интерфейса после нажатия (B[14]) или мана
  failReason(s, pressGT) {
    if (pressGT != null && s.errTime > pressGT) {
      return s.err === 1 ? 'не лицом к цели' : s.err === 2 ? 'слишком далеко' : 'другая ошибка игры';
    }
    if (s.manaPct < 0.1) return 'нет маны';
    return 'нет реакции (прервано, нельзя применить или клавиша не принята)';
  },

  // нас бьёт моб, который не текущая цель: Tab по кругу, пока цель не будет «целиться в игрока» (Q[8])
  async switchToAttacker() {
    for (let i = 0; i < 4 && this.running; i++) {
      const s = await this.tabTarget();
      const p = this.pet();
      if (s.target && s.enemy && s.targetHp > 0 && p && p.ttPlayer) return true;
    }
    return false;
  },

  // После «Притвориться мёртвым»: ждём, пока бой кончится (мобы потеряли цель) — не дольше 25 с, потом встаём
  // коротким шагом назад (движение снимает притворство).
  async waitFeignOver(maxMs = 25000) {
    const t0 = Date.now();
    await sleep(1200);                                        // притворство применяется не мгновенно
    while (this.running && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.hp === 0 || this.dead()) return;                  // всё-таки погиб
      if (!s.fight) break;
      await sleep(500);
    }
    if (this.running) { this.key('S', true); await sleep(250); this.key('S', false); }
  },

  // питомцу нужно лечение: есть, жив, HP ниже порога и лечение на нём ещё не висит (HoT 15 с)
  petNeedsHeal(below) {
    const p = this.pet();
    return !!p && p.exists && !p.dead && !p.mend && p.hpPct < below;
  },

  async fight({ fleeAt = 0.2, timeout = 90000 } = {}) {
    this.running = true;
    const t0 = Date.now(), fightStart = t0;
    let feignAt = 0;
    let lastPress = 0, handledErr = 0, turns = 0, retreats = 0, lastRetreat = 0, deadSince = 0;
    let noAttackSince = 0, hpAtNoAttack = 0, lastSwitch = 0, trapAt = 0;
    const hpHist = [];
    this.fightMobs = 1;
    const cc = this.combatCfg();
    while (this.running) {
      const s = this.state(), hpPct = s.hp / s.hpMax;
      if (s.hp > 0) this.lastAlive = { hpPct: +hpPct.toFixed(2), manaPct: s.manaPct, pet: s.pet };   // для отчёта о смерти
      if (s.hp === 0 || this.dead()) { this.release(); return { result: 'персонаж погиб', ...s }; }   // мёртвый не жмёт клавиши
      if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
      if (hpPct < fleeAt) { this.release(); return { result: 'мало HP — нужен выбор', ...s }; }
      if (Date.now() - t0 > timeout) { this.release(); return { result: 'бой слишком долгий', ...s }; }

      if (!s.target || !s.enemy || s.targetHp === 0) { await this.tabTarget(); continue; }

      // Цель не бьёт ни игрока, ни питомца ≥ 2 с, а HP игрока падает — бьёт кто-то другой: переключиться
      const pp = this.pet();
      if (pp && s.fight && s.target && s.enemy && s.targetHp > 0 && !pp.ttPlayer && !pp.ttPet) {
        if (!noAttackSince) { noAttackSince = Date.now(); hpAtNoAttack = hpPct; }
        else if (Date.now() - noAttackSince >= 2000 && hpPct < hpAtNoAttack - 0.03 && Date.now() - lastSwitch > 3000) {
          lastSwitch = Date.now(); noAttackSince = 0;
          if (await this.switchToAttacker()) {
            this.fightMobs++;
            this.warnings.push('переключился на моба, который бьёт нас');
            if (this.warnings.length > 20) this.warnings.shift();
          }
          continue;
        }
      } else noAttackSince = 0;

      const isReadyKey = k => (s.ready >> (+k - 1)) & 1;
      const spellRec = h => (this.spells() || []).find(r => r.nameHash === h);
      const now = Date.now();
      this.lastKey = this.lastKey || {};
      this.lastSpellAt = this.lastSpellAt || {};

      // Проверка, что нажатие сработало: после удачного применения умение уходит на ГКД/КД (ready=0).
      // Если за 1.2 с оно так и осталось готовым — нажатие не прошло (клавиша не работает, нельзя применить):
      // пропускаем это умение 5 с, чтобы оно не блокировало остальные правила.
      this.pressLog = this.pressLog || {};
      this.pressGT = this.pressGT || {};
      const casting = this.casting();
      this.failedUntil = this.failedUntil || {};
      this.lastOkAt = this.lastOkAt || {};
      for (const h in this.pressLog) {
        const rec = spellRec(+h), at = this.pressLog[h];
        // идёт каст — нажатие сработало (долгое заклинание: ГКД 1.5 с кончается раньше каста 2.5 с,
        // и умение снова «готово», хотя уже читается)
        if (!rec || rec.ready === 0 || casting) { this.lastOkAt[h] = now; delete this.pressLog[h]; delete this.pressGT[h]; continue; }
        if (now - at > 1200) {
          this.failedUntil[h] = now + 5000;
          const why = this.failReason(s, this.pressGT[h]);
          delete this.pressLog[h]; delete this.pressGT[h];
          this.warnings.push('нажатие не сработало: ' + h + ' (клавиша ' + rec.keyCode + '): ' + why);
          if (this.warnings.length > 20) this.warnings.shift();
        }
      }

      // Пока идёт каст/канал — никакие клавиши не жмём и не двигаемся (макрос 9 и ходьба прервали бы чтение лечения)
      if (casting) { await sleep(100); continue; }

      // реакция на свежую ошибку, случившуюся после нашего нажатия
      if (s.errTime > lastPress && s.errTime > handledErr) {
        handledErr = s.errTime;
        if (s.err === 1 && turns < 4) { await this.hold('A', 450); turns++; continue; }  // ~90° поворот
        // стрелку «далеко» в мёртвой зоне не значит «подойди» — он и так вплотную
        const inDeadZone = cc && cc.ranged && this.spellInRange(cc.meleeSpell) === 1;
        if (s.err === 2 && !inDeadZone && now - this.lastHealCastAt() > HEAL_CAST_LOCK_MS) {
          await this.hold('W', 500); continue;                                           // подойти ближе
        }
      }

      // Притвориться мёртвым — последнее средство при HP < 15%: самый приоритетный ход (раньше ловушки и атаки).
      if (cc && cc.feignSpell && s.fight && hpPct < (cc.feignBelowHpPct || 0.15) && now - feignAt > 20000
          && (this.failedUntil[cc.feignSpell] || 0) <= now) {
        const rec = spellRec(cc.feignSpell);
        if (rec && rec.ready === 1) {
          feignAt = now;
          if (await this.pressSpell(cc.feignSpell)) {
            this.warnings.push('притворился мёртвым: HP ' + Math.round(hpPct * 100) + '%');
            if (this.warnings.length > 20) this.warnings.shift();
            await this.waitFeignOver();
            this.release();
            return { result: 'притворился мёртвым', ...this.state() };   // дальше гринд отдыхает (HP низкое)
          }
        }
      }

      // Замораживающая ловушка. HP охотника ниже trapBelowHpPct, и его реально бьют (HP упало ≥ 3% за 2 с):
      //  а) цель держит питомец (она на нём), а охотника бьёт ДРУГОЙ моб — ставим ловушку под ноги (замораживает
      //     соседа, который бьёт нас) и продолжаем бить ту же цель: не отходим и не переключаем цель;
      //  б) цель сама бьёт охотника вплотную — ловушка под ноги, затем пара шагов назад.
      // Лёд снимается любым уроном по мобу, поэтому по замороженному не стреляем (цель в случае «а» — другая).
      // Пока ловушки нет в панели умений (не выучена), правило молча пропускается.
      hpHist.push([now, hpPct]);
      while (hpHist.length && now - hpHist[0][0] > 2000) hpHist.shift();
      const recentDrop = Math.max(...hpHist.map(h => h[1])) - hpPct;
      if (cc && cc.trapSpell && s.fight && hpPct < (cc.trapBelowHpPct || 0.7) && recentDrop >= 0.03
          && now - trapAt > 30000 && (this.failedUntil[cc.trapSpell] || 0) <= now) {
        const rec = spellRec(cc.trapSpell), pt = this.pet();
        const otherAttacks = !!pt && pt.ttPet && !pt.ttPlayer;                              // случай «а»
        const targetAttacks = this.spellInRange(cc.meleeSpell) === 1 && (!pt || pt.ttPlayer);   // случай «б»
        if (rec && rec.ready === 1 && (otherAttacks || targetAttacks)) {
          trapAt = now;
          if (await this.pressSpell(cc.trapSpell)) {
            this.pressLog[cc.trapSpell] = Date.now(); this.pressGT[cc.trapSpell] = s.time;
            this.warnings.push('ловушка: HP ' + Math.round(hpPct * 100) + '%, ' +
                (otherAttacks ? 'бьёт другой моб, цель прежняя' : 'цель вплотную'));
            if (this.warnings.length > 20) this.warnings.shift();
            await sleep(700);                                  // ловушка срабатывает на соседнем мобе
            if (!otherAttacks) await this.retreat(1200);       // «б»: отойти; «а»: стоим и бьём цель дальше
          }
          continue;
        }
      }

      // Стрелок вплотную, а моба держит питомец — отойти и стрелять (не чаще раза в 6 с, максимум 2 за бой).
      // Если моб бьёт самого охотника — не бегать (догонит), бить в ближнем бою.
      // «Вплотную» = Удар ящера достаёт, а выстрел НЕТ (мёртвая зона) и моб ближе ~10 м; держится ≥ 1 с.
      const deadZone = cc && cc.ranged && this.spellInRange(cc.meleeSpell) === 1
          && this.spellInRange(cc.shootSpell) === 0 && (s.tDist & 2);
      if (!deadZone) deadSince = 0; else if (!deadSince) deadSince = now;
      if (deadZone && now - deadSince >= 1000 && s.fight && retreats < 2 && now - lastRetreat > 10000 && hpPct > 0.3
          && now - this.lastHealCastAt() > HEAL_CAST_LOCK_MS) {
        const p = this.pet();
        if (p && p.exists && !p.dead && p.ttPet) {
          deadSince = 0;
          await this.retreat(); retreats++; lastRetreat = Date.now();
          this.warnings.push('отход: моба держит питомец');
          if (this.warnings.length > 20) this.warnings.shift();
          continue;
        }
      }

      let pick = null;
      for (const r of this.activeRotation()) {
        // --- v2: заклинание по хешу названия ---
        if (r.spell) {
          const rec = spellRec(r.spell);
          if ((this.failedUntil[r.spell] || 0) > now) continue;
          // уже нажали и ждём, сработало ли — не жать повторно каждые 250 мс
          if (this.pressLog[r.spell]) continue;
          // лечение питомца — HoT на 15 с: после удачного применения не повторять 14 с
          if (r.maxPetHpPct && now - (this.lastOkAt[r.spell] ?? -1e15) < 14000) continue;
          // repeatMs — не повторять чаще (эффект ещё висит); в новом бою первое применение сразу
          if (r.repeatMs && (this.lastOkAt[r.spell] ?? -1e15) > fightStart && now - this.lastOkAt[r.spell] < r.repeatMs) continue;
          // лечение питомца целится в питомца — дальность до врага для него не важна
          if (!rec || rec.ready !== 1 || (rec.inRange === 0 && !r.maxPetHpPct)) continue;
          // ближний удар стрелка — только когда точно вплотную (неизвестная дальность тоже «нет»)
          if (cc && cc.meleeSpell && r.spell === cc.meleeSpell && rec.inRange !== 1) continue;
          if (r.maxHpPct && hpPct >= r.maxHpPct) continue;
          if (r.maxPetHpPct && !this.petNeedsHeal(r.maxPetHpPct)) continue;
          if (r.outOfCombatOnly && s.fight) continue;
          if (r.waitAfter) {
            const ref = r.waitAfter.spell ? (this.lastSpellAt[r.waitAfter.spell] || 0) : (this.lastKey[r.waitAfter.key] || 0);
            if (now - ref < (r.waitAfter.ms || 0)) continue;   // ещё рано — к следующему правилу
          }
          pick = r; break;
        }
        // --- v1: слот по клавише ---
        if (!isReadyKey(r.key)) continue;
        if (r.maxHpPct && hpPct >= r.maxHpPct) continue;
        if (r.maxPetHpPct && !this.petNeedsHeal(r.maxPetHpPct)) continue;
        if (r.outOfCombatOnly && s.fight) continue;
        if (r.waitAfter) {
          const ref = r.waitAfter.spell ? (this.lastSpellAt[r.waitAfter.spell] || 0) : (this.lastKey[r.waitAfter.key] || 0);
          if (now - ref < (r.waitAfter.ms || 0)) continue;   // ещё рано — к следующему правилу
        }
        pick = r; break;
      }
      if (pick) {
        if (pick.spell) {
          if (await this.pressSpell(pick.spell) && !this.pressLog[pick.spell]) { this.pressLog[pick.spell] = Date.now(); this.pressGT[pick.spell] = s.time; }
        } else {
          await this.tap(pick.key);
          this.lastKey[pick.key] = Date.now();
        }
        lastPress = s.time; turns = 0;
      }
      await sleep(250);
    }
    return { result: 'остановлен вручную', ...this.state() };
  },

  async rest({ hpUntil = 0.8, manaUntil = 0.9, manaBelow = 0.3,
                healKey = '7', healSpell = null, drinkKey = '0', foodKey = null, timeout = 60000 } = {}) {
    // настройки отдыха профиля важнее параметров, пришедших из гринда (там умолчания паладина)
    const pr = PROFILE_REST[this.profile];
    if (pr) ({ healKey, healSpell, drinkKey, foodKey } = { healKey: null, healSpell: null, foodKey: null, drinkKey, ...pr });
    const t0 = Date.now();
    const isReady = (s, k) => (s.ready >> (+k - 1)) & 1;
    let s = this.state();
    const bail = r => ({ result: r, ...this.state() });

    // 0) еда — нажать ОДИН раз (повторное нажатие начинает трапезу заново и тратит ещё порцию)
    let ate = false;
    if (foodKey && s.hp / s.hpMax < hpUntil && !s.fight) {
      await this.tap(foodKey); ate = true; await sleep(500);
    }

    // 1) питьё, если мало маны
    if (drinkKey && s.manaPct < manaBelow) {
      await this.tap(drinkKey);
      while (Date.now() - t0 < timeout) {
        await sleep(1000); s = this.state();
        if (s.fight) return bail('бой во время отдыха');
        if (s.manaPct >= manaUntil) break;
      }
    }
    // 2) лечение заклинанием (до 3 раз)
    for (let i = 0; i < 3 && (healSpell || healKey); i++) {
      s = this.state();
      if (s.fight) return bail('бой во время отдыха');
      if (s.hp / s.hpMax >= hpUntil || s.manaPct < 0.15) break;
      if (healSpell) {
        const rec = (this.spells() || []).find(r => r.nameHash === healSpell);
        if (!rec || rec.ready !== 1 || rec.inRange === 0) { await sleep(500); continue; }
        await this.pressSpell(healSpell);
      } else {
        if (!isReady(s, healKey)) { await sleep(500); continue; }
        await this.tap(healKey);
      }
      await sleep(3000);                       // время чтения заклинания
    }
    // 3) добрать естественным отдыхом; питомца подлечить его правилом из ротации
    const petRule = this.activeRotation().find(r => r.maxPetHpPct && r.spell);
    const petOk = () => { const p = this.pet(); return !petRule || !p || !p.exists || p.dead || p.hpPct >= 0.8; };
    while (Date.now() - t0 < timeout) {
      s = this.state();
      if (s.fight) return bail('бой во время отдыха');
      if (petRule && this.petNeedsHeal(0.8)) {
        const rec = (this.spells() || []).find(r => r.nameHash === petRule.spell);
        if (rec && rec.ready === 1) await this.pressSpell(petRule.spell);
      }
      if (s.hp / s.hpMax >= hpUntil && petOk() && (!ate || !drinkKey || s.manaPct >= manaBelow)) return bail('отдохнул');
      await sleep(1000);
    }
    return bail('отдых: таймаут');
  }
});
