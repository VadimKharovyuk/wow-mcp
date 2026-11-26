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
      paladin: { // Аварийное лечение по порогу HP (проверяется по порядку, самое срочное первым), выше ротации.
                 // Возложение рук (8) — HP < 9%, Свет небес (7) — HP < 35%, Вспышка света (U) — HP < 60%.
                 emergency: [
                   { spell: 1396923377 /* Возложение рук */, below: 0.09, inFightOnly: true },
                   { spell: 1488691226 /* Свет небес */,     below: 0.35 },
                   { spell: 326428551  /* Вспышка света */,  below: 0.60 },
                 ],
                 // Лечение из выученной ротации — только когда HP ниже порога (иначе жмётся при полном HP вне боя)
                 // Баффы держит ensureBuffs по таймеру (buffs ниже) — в ротации их нет (иначе жмутся при каждой готовности)
                 // приоритет боевых умений (после экстренных с условием по HP): 1 Молот праведника, 2 Правосудие света, 3 Щит мстителя
                 priority: [1363607377 /* Молот праведника */, 2037478445 /* Правосудие света */,
                            { spell: 2008405423 /* Щит мстителя (3) */, minAttackers: 2 /* только когда бьют не меньше двух врагов */ },
                            { spell: 1191036009 /* Освящение (9, id 20116/26573) */, minAttackers: 3 /* только когда бьют трое и больше */ }],
                 rotationSkip: [1764839983 /* Печать праведности (G) */, 1453980521 /* Печать повиновения (G) */, 516635130 /* Благословение королей */,
                                2105405526 /* Очищение — не жать впустую в бою */, 1290206130 /* Аура воздаяния */,
                                242961069 /* Благословение могущества */, 1433786557 /* Благословение мудрости */,
                                904078709 /* Камень возвращения */,
                                1791702616 /* Благословение неприкосновенности (Y, id 20911) */,
                                489876339 /* Праведное неистовство (T) */,
                                980890529 /* Святая клятва (H) — держится по таймеру из buffs */],
                 healGuard: { 666458133: 0.60 /* Вспышка света (ранги) */, 326428551: 0.60 /* Вспышка света, другое написание */,
                              1488691226: 0.35 /* Свет небес */,
                              1396923377: 0.09 /* Возложение рук — только при HP < 9% (как в аварийном блоке) */ },
                 mountKey: 'L' /* Призыв боевого коня */, interactKey: 'J' /* «Взаимодействовать с целью» (назначить в игре: Esc → Назначение клавиш): сам разворачивает и подводит к цели */, closeInSpell: 1363607377 /* Молот праведника: вне его дальности — подбегаем */, autoAttack: true, stepBackOnFacing: true,
                 finishers: [1101686797 /* Молот гнева (клавиша U, id 24275): только при HP цели < 20% */],
                 pullSpells: [1700418603 /* Длань возмездия */, 1953631017 /* «Дань возмездия» — на случай другого написания */],
                 buffs: [
        { key: 'G', name: 'Печать повиновения', everyMs: 20 * 60000, inFight: true, spell: 1453980521 },   // держится 30 мин; обновляем раз в 20 мин (запас на неудачное нажатие), в том числе в бою
        { key: 'T', name: 'Праведное неистовство', everyMs: 25 * 60000 },
        { key: 'Y', name: 'Благословение неприкосновенности', everyMs: 9 * 60000, inFight: true, spell: 1791702616 },   // держится 10 мин; обновляем раз в 9 мин, в том числе в бою
        { key: 'H', name: 'Святая клятва', everyMs: 60000, inFight: true, spell: 980890529 },   // раз в 60 с, в том числе в бою; лишнее нажатие просто обновляет
      ] },
    };
    return C[this.profile] || null;
  },
  // ротация для боя: закреплённая в профиле, иначе выученная/заданная (this.rotation)
  // Порядок: сначала правила с условием по HP (экстренные: maxHpPct / maxPetHpPct) — они всегда выше; затем cc.priority
  // (умения профиля по хешу, в заданном порядке); потом остальное из ротации без дублей.
  activeRotation() {
    const c = this.combatCfg();
    const base = (c && c.rotation) || this.rotation || [];
    if (!c || !c.priority || !c.priority.length) return base;
    const cond = r => !!(r.maxHpPct || r.maxPetHpPct);
    const prio = c.priority.map(h => (typeof h === 'object' ? h : { spell: h }));     // число (хеш) или правило с условием
    const prioSpells = prio.map(r => r.spell);
    return base.filter(cond)
      .concat(prio)
      .concat(base.filter(r => !cond(r) && !(r.spell && prioSpells.includes(r.spell))));
  },
  // запись заклинания-открывателя боя (cc.pullSpells) на панели умений или null
  pullRec(cc) {
    if (!cc || !cc.pullSpells) return null;
    const sp = this.spells() || [];
    for (const h of cc.pullSpells) { const r = sp.find(x => x.nameHash === h); if (r) return r; }
    return null;
  },
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
      while (Date.now() - t0 < ms && this.running) {
        await sleep(50);
        const s = this.state();
        if (s.target && s.targetHp === 0) break;    // моб уже умер — не дошагивать от трупа (лут вплотную)
      }
    } finally {
      this.key('S', false);
    }
  },

  // когда в последний раз нажимали лечение (игрока или питомца) — его чтение прерывается движением
  lastHealCastAt() {
    this.lastKey = this.lastKey || {}; this.lastSpellAt = this.lastSpellAt || {};
    let t = this.lastKey['4'] || 0;                               // v1: паладин лечит на 4
    const cc = this.combatCfg();
    if (cc && cc.emergency) for (const e of cc.emergency) { const v = this.lastSpellAt[e.spell] || 0; if (v > t) t = v; }
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
  // turns — сколько раз можно довернуться на ~120° и повторить Tab: Tab берёт только врагов впереди, а атакующий может быть сзади.
  // Не нашли — возвращаемся к прежнему курсу.
  async switchToAttacker({ turns = 0 } = {}) {
    this.lastSwitchTabs = 0; this.lastSwitchTurns = 0; this.lastSwitchInfo = [];
    const f0 = this.state().facing;
    for (let t = 0; t <= turns && this.running; t++) {
      if (t > 0) { await this.turnTo(this.state().facing + 2.1, 2500, () => !this.running); this.lastSwitchTurns = t; }
      for (let i = 0; i < (t === 0 ? 4 : 3) && this.running; i++) {
        const s = await this.tabTarget();
        this.lastSwitchTabs++;
        const p = this.pet();
        // диагностика (в журнал): что показал каждый Tab — цель есть, враг, уровень, HP цели %, бьёт ли нас, дистанция, номер поворота
        this.lastSwitchInfo.push({ r: t, tg: s.target ? 1 : 0, en: s.enemy ? 1 : 0, lvl: s.tLvl, hp: Math.round((s.targetHpPct || 0) * 100), tp: p && p.ttPlayer ? 1 : 0, d: s.tDist });
        if (s.target && s.enemy && s.targetHp > 0 && p && p.ttPlayer) return true;
      }
    }
    if (this.lastSwitchTurns && this.running) await this.turnTo(f0, 2500, () => !this.running);
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

  async fight({ fleeAt = 0.2, timeout = 140000, hardTimeout = 360000 } = {}) {
    this.running = true;
    const t0 = Date.now(), fightStart = t0;
    let feignAt = 0;
    let lastPress = 0, handledErr = 0, turns = 0, retreats = 0, lastRetreat = 0, deadSince = 0;
    let noAttackSince = 0, hpAtNoAttack = 0, lastSwitch = 0, trapAt = 0, pullTries = 0, lastPull = 0, lastAutoAtk = 0, stepBacks = 0, lastAttackerTry = 0, lastFinish = 0, openerWaited = false, openerPressed = false, openerNoted = false, lastFastSwitch = 0, skipLogAt = {}, faceEpisodes = 0, lastFaceAt = 0;
    // вращение/поиск атакующего: только если мы не наносим урон и нас реально бьют; после неудачи — откат 8 → 15 → 30 с
    let dealtAt = 0, prevTHp = null, searchFails = 0, nextSearchAt = 0;
    // «Взаимодействовать с целью» (interactKey): клиент сам разворачивает персонажа и ведёт к цели; пока идёт автоподход, сами не вертимся и не бежим
    let lastInteractAt = 0, interactUntil = 0, interactTries = 0, lastInteractTime = 0;
    // «бой без противника»: боевой флаг висит, а ≥ idleFightMs нет ни нашего урона, ни полученного, ни траты маны/каста
    const idleMs = (this.grindCfg && this.grindCfg.idleFightMs) || 20000;
    let idleActiveAt = Date.now(), prevMana = null, prevMyHp = null;
    const hpSamples = [];
    const BACKOFF = [8000, 15000, 30000];
    // «нет прогресса»: цель недостижима (за колонной, на уступе, эвейд) — HP цели не меняется, нас никто не бьёт
    const npMs = (this.grindCfg && this.grindCfg.noProgressMs) || 8000;
    let progAt = Date.now(), progHp = null, progMyHp = null, progTHp = null, lastActiveAt = Date.now(), longNoted = false, lastInFightBuff = 0, closeSpent = 0, closeActive = false, closeD0 = 0, closeT0 = 0;
    const hpHist = [];
    this.fightMobs = 1; this.fightOpener = null; this.fightMaxAttackers = 0;
    const cc = this.combatCfg();
    while (this.running) {
      const s = this.state(), hpPct = s.hp / s.hpMax;
      if (s.attackers != null && s.attackers > this.fightMaxAttackers) this.fightMaxAttackers = s.attackers;   // для журнала боя
      if (s.hp > 0) this.lastAlive = { hpPct: +hpPct.toFixed(2), manaPct: s.manaPct, pet: s.pet };   // для отчёта о смерти
      if (s.hp === 0 || this.dead()) { this.release(); return { result: 'персонаж погиб', ...s }; }   // мёртвый не жмёт клавиши
      if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
      if (hpPct < fleeAt) { this.release(); return { result: 'мало HP — нужен выбор', ...s }; }
      // Мягкий таймаут: плотность мобов — бой с 3–4 врагами бывает долгим. Пока есть прогресс (HP цели падает или нас бьют),
      // бьёмся дальше, но не дольше hardTimeout; без прогресса (или при пределе) — прежний отказ.
      if (progTHp == null || s.targetHp !== progTHp || (s.attackers != null && s.attackers >= 1)) lastActiveAt = Date.now();
      progTHp = s.targetHp;
      if (Date.now() - t0 > timeout) {
        if (Date.now() - t0 < hardTimeout && Date.now() - lastActiveAt < ((this.grindCfg && this.grindCfg.activeWindowMs) || 20000)) {
          if (!longNoted) {
            longNoted = true;
            this.log && this.log('warn', 'fight', 'долгий бой: продолжаю (есть прогресс)', { sec: Math.round((Date.now() - t0) / 1000), attackers: s.attackers, hp: Math.round(hpPct * 100), mana: Math.round((s.manaPct || 0) * 100), mobs: this.fightMobs });
          }
        } else { this.release(); return { result: 'бой слишком долгий', ...s }; }
      }

      // Учёт: когда последний раз падало HP цели (мы наносим урон) и сколько HP потеряли мы за последние ~4 с
      const tnow = Date.now();
      if (prevTHp != null && s.target && s.targetHp < prevTHp) { dealtAt = tnow; interactTries = 0; }
      prevTHp = s.target ? s.targetHp : null;
      hpSamples.push({ t: tnow, p: hpPct });
      while (hpSamples.length && tnow - hpSamples[0].t > 4000) hpSamples.shift();
      const lostHp = Math.max(...hpSamples.map(x => x.p)) - hpPct;
      const canTurn = tnow - dealtAt > 3000 && lostHp >= 0.03 && tnow >= interactUntil;   // не бьём никого ≥ 3 с, а наше HP упало ≥ 3% — только тогда вертимся
      if (s.attackers === 0) { searchFails = 0; nextSearchAt = 0; }   // бьют уже не нас — откат сбрасываем

      // Простой в бою: любая активность (урон по цели, урон по нам, нас бьют, трата маны, каст) сбрасывает таймер. Не было ничего ≥ idleMs,
      // а боевой флаг висит (зависший флаг, моб за краем видимости, зарытая цель) — выходим с результатом «бой без противника».
      {
        const mana = s.manaPct || 0;
        const spent = prevMana != null && mana < prevMana - 0.004;
        const hit = prevMyHp != null && s.hp < prevMyHp;
        prevMana = mana; prevMyHp = s.hp;
        if (dealtAt > idleActiveAt) idleActiveAt = dealtAt;
        if (spent || hit || (s.attackers != null && s.attackers >= 1) || this.casting()) idleActiveAt = tnow;
        if (s.fight && tnow - idleActiveAt >= idleMs) { this.release(); return { result: 'бой без противника', idleMs: tnow - idleActiveAt, ...s }; }
      }

      if (!s.target || !s.enemy || s.targetHp === 0) {
        // цель умерла/пропала, а бой идёт: берём того, кто бьёт нас (Tab до «целится в игрока»), а не случайного врага.
        // Не чаще раза в 1,5 с — без нападающих иначе каждый круг тратил бы до 4 Tab.
        if (s.fight && Date.now() - lastAttackerTry > 1500 && Date.now() >= nextSearchAt) {
          lastAttackerTry = Date.now();
          const was = s.target ? (s.targetHp === 0 ? 'мёртвая' : 'не враг') : 'нет';
          const ok = await this.switchToAttacker({ turns: s.attackers >= 1 && canTurn ? 2 : 0 });
          if (ok) { searchFails = 0; nextSearchAt = 0; }
          else if (s.attackers >= 1) { nextSearchAt = Date.now() + BACKOFF[Math.min(searchFails, BACKOFF.length - 1)]; searchFails++; }
          const n = this.lastSwitchTabs;
          this.warnings.push('переключился после смерти цели: ' + (ok ? 'на атакующего' : 'атакующего нет, взят ближайший')
              + ' (Tab ' + n + ', была цель: ' + was + ', HP ' + Math.round(hpPct * 100) + '%)');
          if (this.warnings.length > 20) this.warnings.shift();
          this.log && this.log('info', 'fight', 'поиск атакующего после цели «' + was + '»: ' + (ok ? 'найден' : 'не найден'),
              { found: ok, attackers: s.attackers, tabs: this.lastSwitchTabs, turns: this.lastSwitchTurns, hp: Math.round(hpPct * 100), canTurn, fails: searchFails, backoffMs: ok ? 0 : nextSearchAt - Date.now(), info: this.lastSwitchInfo });
          if (ok) this.fightMobs++;
          continue;
        }
        await this.tabTarget(); continue;
      }

      // Нет прогресса ≥ npMs: HP цели не меняется, наше HP не падает, цель не бьёт ни нас, ни питомца, нас никто не бьёт, каст не идёт —
      // до цели не достать (нет линии видимости, уступ, забор). Бой может даже не начаться (s.fight = false). Отказываемся от цели.
      {
        const pn = this.pet();
        const active = progHp == null || s.targetHp !== progHp || (progMyHp != null && s.hp < progMyHp)
            || (s.attackers != null && s.attackers >= 1) || (pn && (pn.ttPlayer || pn.ttPet)) || this.casting();
        progHp = s.targetHp; progMyHp = s.hp;
        if (active) progAt = Date.now();
        else if (Date.now() - progAt >= npMs) { this.release(); return { result: 'цель недостижима', waitedMs: Date.now() - progAt, ...s }; }
      }

      // Быстрое переключение: нас уже бьют (счётчик врагов из Lua ≥ 1), а выбранная цель нетронута и нас не бьёт
      // (например, ещё не подошла) — не ждём 5 с, сразу берём того, кто бьёт. Не чаще раза в 3 с; 2 неудачи подряд — отстаём
      // (атакующий вне экрана/дальний). Число врагов неизвестно (старый Lua) — работает прежнее правило ниже.
      {
        const pq = this.pet();
        // eligible — нас бьют (attackers >= 1) в бою; только тогда либо переключаемся, либо пишем причину отказа
        const eligible = !!pq && s.fight && s.attackers >= 1;
        const why = !eligible ? null
            : !s.target ? 'нет цели' : !s.enemy ? 'цель не враг' : !(s.targetHp > 0) ? 'цель мертва'
            : !(s.targetHpPct >= 0.99) ? 'цель тронута (' + Math.round((s.targetHpPct || 0) * 100) + '%)'
            : pq.ttPlayer ? 'цель бьёт игрока' : pq.ttPet ? 'цель бьёт питомца'
            : Date.now() < nextSearchAt ? 'откат после неудачного поиска (' + Math.round((nextSearchAt - Date.now()) / 1000) + ' с)'
            : Date.now() - lastFastSwitch <= 3000 ? 'пауза после поиска' : null;
        // диагностика: нас бьют, а быстрое переключение не сработало — пишем причину (раз в 6 с на причину)
        if (why && Date.now() - (skipLogAt[why] || 0) > 6000) {
          skipLogAt[why] = Date.now();
          this.log && this.log('info', 'fight', 'быстрое переключение пропущено: ' + why,
              { attackers: s.attackers, tDist: s.tDist, tLvl: s.tLvl, hp: Math.round(hpPct * 100), ttPlayer: pq && pq.ttPlayer ? 1 : 0, tHpPct: Math.round((s.targetHpPct || 0) * 100) });
        }
        if (eligible && !why) {
          lastFastSwitch = Date.now();
          const tDist = s.tDist, att = s.attackers;
          const ok = await this.switchToAttacker({ turns: canTurn ? 2 : 0 });
          if (ok) { searchFails = 0; nextSearchAt = 0; }
          else { nextSearchAt = Date.now() + BACKOFF[Math.min(searchFails, BACKOFF.length - 1)]; searchFails++; }   // не нашли: откат 8 → 15 → 30 с
          if (ok) this.fightMobs++;
          this.log('info', 'fight', 'быстрое переключение: ' + (ok ? 'взят атакующий' : 'атакующего не нашёл'),
              { attackers: att, tDist, tabs: this.lastSwitchTabs, turns: this.lastSwitchTurns, found: ok, hp: Math.round(hpPct * 100), canTurn, fails: searchFails, backoffMs: ok ? 0 : nextSearchAt - Date.now(), info: this.lastSwitchInfo });
          continue;
        }
      }

      // Цель не бьёт ни игрока, ни питомца ≥ 2 с, а HP игрока падает — бьёт кто-то другой: переключиться
      const pp = this.pet();
      // Не бросаем моба, которого уже начали бить (доля HP цели < 99%): сначала добиваем. Переключаемся только с нетронутой
      // цели, которая не бьёт нас > 5 с, и не чаще раза в 10 с — иначе в бою с двумя мобами цель прыгает и никто не умирает.
      if (pp && s.fight && s.target && s.enemy && s.targetHp > 0 && s.targetHpPct >= 0.99 && !pp.ttPlayer && !pp.ttPet) {
        if (!noAttackSince) { noAttackSince = Date.now(); hpAtNoAttack = hpPct; }
        else if (Date.now() - noAttackSince >= 5000 && hpPct < hpAtNoAttack - 0.03 && Date.now() - lastSwitch > 10000) {
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
        // ближний бой: моб за спиной — пара шагов назад (проходим сквозь него и он снова впереди); не помогло дважды — поворот
        if (s.err === 1 && cc && cc.stepBackOnFacing && stepBacks < 2) { stepBacks++; await this.retreat(700); continue; }
        if (s.err === 1 && turns < 4) { await this.hold('A', 450); turns++; continue; }  // ~90° поворот
        // стрелку «далеко» в мёртвой зоне не значит «подойди» — он и так вплотную
        const inDeadZone = cc && cc.ranged && this.spellInRange(cc.meleeSpell) === 1;
        if (s.err === 2 && !inDeadZone && now - this.lastHealCastAt() > HEAL_CAST_LOCK_MS) {
          await this.hold('W', 500); continue;                                           // подойти ближе
        }
      }

      // Аварийное лечение профиля (паладин): первое подходящее по порогу и готовое; один раз и дальше цикл заново
      if (cc && cc.emergency) {
        let did = false;
        for (const e of cc.emergency) {
          if (hpPct >= e.below || !s.fight) continue;            // лечим только в бою (вне боя — отдых)
          if ((this.failedUntil[e.spell] || 0) > now || this.pressLog[e.spell]) continue;
          const rec = spellRec(e.spell);
          if (!rec || rec.ready !== 1) continue;
          // верхом не лечим; HP перечитываем прямо перед нажатием (за круг цикла он мог вырасти или быть прочитан криво)
          if (this.mounted && this.mounted()) continue;
          { const s2 = this.state(); if (s2.hp / s2.hpMax >= e.below) continue; }
          if (await this.pressSpell(e.spell)) {
            (this.healLog = this.healLog || []).push({ spell: e.spell, hp: Math.round(hpPct * 100), fight: !!s.fight,
              mounted: !!(this.mounted && this.mounted()), at: new Date().toTimeString().slice(0, 8) });
            if (this.healLog.length > 10) this.healLog.shift();
            this.log && this.log('info', 'heal', 'аварийное лечение ' + e.spell + ': HP ' + Math.round(hpPct * 100) + '%',
                { spell: e.spell, hp: Math.round(hpPct * 100), inFight: !!s.fight, mounted: !!(this.mounted && this.mounted()) });
            this.pressLog[e.spell] = Date.now(); this.pressGT[e.spell] = s.time;
            this.warnings.push('лечение (' + e.spell + '): HP ' + Math.round(hpPct * 100) + '%');
            if (this.warnings.length > 20) this.warnings.shift();
          }
          did = true; break;
        }
        if (did) { await sleep(250); continue; }
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

      // Интеракт: бой идёт, цель выбрана, мы её не бьём ≥ 2 с, а умения не достают (вне дальности) или цель не перед нами (ошибка «не лицом») —
      // нажимаем «Взаимодействовать с целью»: клиент сам разворачивает персонажа и подводит к цели (дальний моб, цель сзади, зарытая цель
      // без полосы здоровья). Не чаще раза в 2,5 с, до 8 попыток подряд без урона; 3 с после нажатия сами не вертимся и не бежим.
      if (cc && cc.interactKey && s.fight && s.target && s.enemy && s.targetHp > 0 && !casting
          && tnow - Math.max(dealtAt, t0) > 2000 && tnow - lastInteractAt > 2500 && interactTries < 8) {   // t0 — начало боя: первые 2 с даём шанс ротации
        const rngI = cc.closeInSpell ? this.spellInRange(cc.closeInSpell) : -1;
        const why = rngI === 0 ? 'вне дальности' : (s.err === 1 && s.errTime > lastInteractTime ? 'не лицом' : null);
        if (why) {
          lastInteractAt = tnow; interactUntil = tnow + 3000; interactTries++; lastInteractTime = s.time;
          await this.tap(cc.interactKey);
          this.log && this.log('info', 'fight', 'интеракт: нажата (' + why + ')', { why, tDist: s.tDist, attackers: s.attackers, hp: Math.round(hpPct * 100), tries: interactTries });
          continue;
        }
      }

      // Цель выбрана и бьёт нас, но её полосы нет на экране — она сзади или сбоку (Tab, скрытая цель, моб обошёл): доворачиваемся по
      // кругу шагами ~90°, пока полоса не появится, и наводимся на неё. Не больше 3 заходов за бой, не чаще раза в 4 с.
      if (s.fight && s.target && s.enemy && s.targetHp > 0 && !casting && faceEpisodes < 3 && now - lastFaceAt > 4000 && canTurn && this.targetOffscreen()) {
        lastFaceAt = now; faceEpisodes++;
        let steps = 0, found = false;
        for (; steps < 3 && this.running; steps++) {
          await this.turnTo(this.state().facing + 1.57, 1500, () => !this.running);
          await sleep(250);                                   // полосы обновляются раз в 0.1 с
          if (!this.targetOffscreen()) { found = true; steps++; break; }
        }
        if (found) { const pl = this.plateAim && this.plateAim(); if (pl && Math.abs(pl.fx - 0.5) > 0.08 && this.aimBy) await this.aimBy(pl.fx, () => !this.running); }
        this.log && this.log('info', 'fight', 'доворот к цели, которая вне экрана: ' + (found ? 'нашёл' : 'не нашёл'), { steps, found, attackers: s.attackers, tDist: s.tDist, hp: Math.round(hpPct * 100), episode: faceEpisodes });
        continue;
      }

      // Баффы с inFight (паладин: Святая клятва): интервал вышел — обновляем и в бою. Не во время каста, не чаще раза в 3 с.
      if (cc && cc.buffs && s.fight && now - lastInFightBuff > 3000) {
        this.buffAt = this.buffAt || {};
        const b = cc.buffs.find(x => x.inFight && now - (this.buffAt[x.key] || 0) >= x.everyMs);
        if (b) {
          const rec = b.spell ? spellRec(b.spell) : null;
          if (!b.spell || (rec && rec.ready === 1)) {
            lastInFightBuff = now;
            this.buffAt[b.key] = now;
            if (b.spell) await this.pressSpell(b.spell); else await this.tap(b.key);
            this.log && this.log('info', 'buff', 'в бою обновлён бафф: ' + b.name, { inFight: true, hp: Math.round(hpPct * 100), attackers: s.attackers });
            continue;
          }
        }
      }

      // Сближение: ближний профиль (cc.closeInSpell — умение ближнего боя на панели), бой идёт, а цель вне его дальности —
      // моб бьёт издалека (кастер, лучник). Доворачиваемся к цели и подбегаем короткими кусками; не больше 10 с за бой.
      if (cc && cc.closeInSpell && s.fight && s.target && s.enemy && s.targetHp > 0 && closeSpent < 10000 && tnow >= interactUntil && !this.targetOffscreen()
          && now - this.lastHealCastAt() > HEAL_CAST_LOCK_MS) {
        const rng = this.spellInRange(cc.closeInSpell);
        if (rng === 0) {
          const c0 = Date.now();
          if (!closeActive) { closeActive = true; closeD0 = s.tDist; closeT0 = c0; }
          const pl = this.plateAim && this.plateAim();
          if (pl && Math.abs(pl.fx - 0.5) > 0.08 && this.aimBy) await this.aimBy(pl.fx, () => !this.running);
          await this.hold('W', 350);
          closeSpent += Date.now() - c0;
          if (closeSpent >= 10000) this.log && this.log('warn', 'fight', 'сближение: не вышло за 10 с', { tDist0: closeD0, tDist1: this.state().tDist, attackers: s.attackers });
          continue;
        }
        if (rng === 1 && closeActive) {
          closeActive = false;
          this.log && this.log('info', 'fight', 'сближение: подошёл', { ms: Date.now() - closeT0, tDist0: closeD0, tDist1: s.tDist, attackers: s.attackers });
        }
      }

      // Бой начинаем заклинанием-открывателем (паладин: Длань возмездия): как только достаёт до цели.
      // До 3 попыток с паузой ≥ 1 с, потом обычная ротация. Нет на панели — правило молча пропускается.
      if (cc && cc.pullSpells && !s.fight && pullTries < 3 && now - lastPull > 1000) {
        let pr = this.pullRec(cc);
        // готовность и дальность в spells() обновляются раз в 0.5 с: цель уже в зоне (ближе ~28 ярдов), а умение «не готово /
        // вне дальности» — один раз подождём до 0.8 с, вместо того чтобы начать бой обычным ударом без открывателя
        if (pr && !(pr.ready === 1 && pr.inRange !== 0) && (s.tDist & 1) && !openerWaited) {
          openerWaited = true;
          const w0 = Date.now();
          while (Date.now() - w0 < 800 && this.running && !this.state().fight) {
            await sleep(100);
            pr = this.pullRec(cc);
            if (pr && pr.ready === 1 && pr.inRange !== 0) break;
          }
        }
        if (pr && pr.ready === 1 && pr.inRange !== 0) {
          pullTries++; lastPull = now; openerPressed = true;
          this.fightOpener = 'нажата (попытка ' + pullTries + ')';
          await this.pressSpell(pr.nameHash);
          continue;
        }
        if (pr) this.fightOpener = 'не нажата: ' + (pr.ready !== 1 ? 'не готова' : 'вне дальности') + ', tDist ' + s.tDist;
        else this.fightOpener = 'не нажата: умения нет на панели';
      }
      // бой начался раньше, чем открыватель успел: причина в журнал (поле opener у записи боя)
      if (cc && cc.pullSpells && s.fight && !openerPressed && !openerNoted) {
        openerNoted = true;
        this.fightOpener = 'моб начал бой первым' + (this.fightOpener ? ' (' + this.fightOpener + ')' : '');
      }

      // Добивающие умения (паладин: Молот гнева — игра считает его доступным, только когда у цели < 20% HP): готовность
      // берём из игры (IsUsableAction), так что нажмётся ровно тогда. Приоритет выше обычной ротации; не чаще раза в 1,5 с.
      if (cc && cc.finishers && s.fight && s.target && s.enemy && s.targetHp > 0 && now - lastFinish > 1500) {
        const fr = cc.finishers.map(spellRec).find(r => r && r.ready === 1 && r.inRange !== 0);
        if (fr) { lastFinish = now; await this.pressSpell(fr.nameHash); continue; }
      }

      // Ближний бой: включаем автоатаку командой /startattack (не переключатель — выключить ею нельзя), как только
      // цель вплотную; повтор не чаще раза в 3 с (цель сменилась / автоатака сбилась). Не прерывает каст.
      if (cc && cc.autoAttack && s.target && s.enemy && s.targetHp > 0 && (s.tDist & 2) && now - lastAutoAtk > 3000) {
        lastAutoAtk = now;
        await chat('/startattack');
        continue;
      }

      let pick = null;
      for (const r of this.activeRotation()) {
        // --- v2: заклинание по хешу названия ---
        if (r.spell) {
          // открыватель боя (pullSpells) жмётся один раз до боя, в ротации его нет
          if (cc && cc.pullSpells && cc.pullSpells.includes(r.spell)) continue;
          if (cc && cc.rotationSkip && cc.rotationSkip.includes(r.spell)) continue;
          if (cc && cc.healGuard && cc.healGuard[r.spell] != null && hpPct >= cc.healGuard[r.spell]) continue;
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
          if (!this.attackersOk(r)) continue;                      // minAttackers: нужно столько врагов, бьющих игрока
          if ((r.outOfCombatOnly || r.spell === 1700418603 || r.key === 'O') && s.fight) continue;
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
        if ((r.outOfCombatOnly || r.spell === 1700418603 || r.key === 'O') && s.fight) continue;
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
