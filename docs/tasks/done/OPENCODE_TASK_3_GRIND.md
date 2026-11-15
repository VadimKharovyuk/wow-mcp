# Задание 3: цикл гринда («качайся тут»)

Проект: `/Users/vadim/Downloads/wow-mcp`. Работают: moveTo/go, fight (ротация по маске ready), loot, rest, reload_bot.
Цель: одна задача `grind`, которая сама крутит цикл **найти моба → подойти → бой → лут → отдых → повтор**
в радиусе вокруг стартовой точки и возвращает результат только по итогам или при проблеме.

Перед кодом — коротко покажи план изменений.

---

## Часть 1. Lua: информация о цели

Добавь в КОНЕЦ `src/main/resources/lua-setup.txt` три строки (каждая ≤ 255 символов — проверь):

```
/run function X() local t="target" local d=UnitLevel(t) B[18]=d==-1 and 99 or d-UnitLevel("player") B[19]=(UnitIsPlayer(t) and 1 or 0)+(UnitClassification(t)~="normal" and 2 or 0) end
/run function Y() local t="target" B[19]=B[19]+(UnitIsTapped(t) and not UnitIsTappedByPlayer(t) and 4 or 0) B[20]=(CheckInteractDistance(t,4) and 1 or 0)+(CheckInteractDistance(t,3) and 2 or 0) end
/run local w=W W=function() w() X() Y() end
```

| Ячейка | Поле в state() | Смысл |
|---|---|---|
| `B[18]` | `tLvl` | уровень цели минус уровень игрока; 99 = «??» (череп) |
| `B[19]` | `tFlags` | битовая маска: 1 = игрок, 2 = элита/редкий, 4 = моб уже «чужой» (тапнут другим игроком) |
| `B[20]` | `tDist` | 1 = цель ближе ~28 м, 2 = ближе ~10 м (биты складываются) |

Порядок строк важен: обёртка `W` должна идти после определения `X` и `Y`, а `W` уже определена выше в файле.
`lua-setup.txt` читается из classpath при старте → после правки нужен `./deploy.sh`, `reload_bot` не хватит.

## Часть 2. bot.js

### 2.1 `00-core.js` — разделить «отпустить клавиши» и «остановить всё»

Сейчас moveTo/fight по завершении вызывают `this.stop()`. Для цикла гринда это плохо: внутренняя остановка подзадачи не должна останавливать сам гринд.

```js
release() { this.running = false; for (const k in this.held) this.key(k, false); },
stop()    { this.grinding = false; this.release(); },   // ручная/экстренная остановка — гасит и гринд
```
Во всех модулях (moveTo, fight, rest, loot, go) внутренние `this.stop()` заменить на `this.release()`.
Инструмент `stop` в Java по-прежнему вызывает `bot.stop()`.

### 2.2 `00-core.js` — state() и report()

В `state()` добавить: `tLvl: g(17), tFlags: g(18), tDist: g(19)`.
В `report()` добавить поле `grind: this.grindStats || null`.

### 2.3 Новый модуль `40-grind.js` (+ добавить в manifest.txt)

```js
Object.assign(bot, {
  grindCfg: {
    radius: 2.5,        // радиус охоты вокруг стартовой точки (единицы карты)
    minLvlDiff: -5,     // не трогать мобов слабее (серые — без опыта)
    maxLvlDiff: 2,      // и сильнее себя больше чем на 2 уровня
    restBelow: 0.6,     // отдыхать, если HP ниже 60%
    pullKey: '3',       // умение для агро издалека (по маске ready видно, что цель в радиусе)
    maxKills: 10,       // 0 = без лимита
    maxMinutes: 30,
  },
  grindStats: null,

  // null — цель подходит; иначе строка-причина отказа
  targetProblem(s) {
    if (!s.target || !s.enemy || s.targetHp === 0) return 'нет цели';
    if (s.tFlags & 1) return 'игрок';
    if (s.tFlags & 2) return 'элита/редкий';
    if (s.tFlags & 4) return 'чужой моб';
    if (s.tLvl > this.grindCfg.maxLvlDiff) return 'слишком сильный';
    if (s.tLvl < this.grindCfg.minLvlDiff) return 'слишком слабый';
    return null;
  },

  // Tab по кругу: 3 нажатия, поворот ~60°, и так 6 раз (полный оборот)
  async findTarget() {
    for (let turn = 0; turn < 6 && this.grinding; turn++) {
      for (let i = 0; i < 3 && this.grinding; i++) {
        await this.tap('TAB'); await sleep(250);
        const s = this.state();
        if (!this.targetProblem(s)) return s;
      }
      await this.hold('A', 330); await sleep(150);
    }
    await chat('/cleartarget');
    return null;
  },

  // подойти, пока умение агро не станет доступно (цель в его радиусе)
  async approach(maxMs = 6000) {
    const isReady = k => (this.state().ready >> (+k - 1)) & 1;
    const t0 = Date.now();
    this.key('W', true);
    while (this.grinding && Date.now() - t0 < maxMs) {
      const s = this.state();
      if (s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2)) break;
      await sleep(150);
    }
    this.key('W', false);
    const s = this.state();
    return s.fight || isReady(this.grindCfg.pullKey) || (s.tDist & 2);
  },

  // бой + лут; возвращает null если всё ок, иначе причину для выхода из гринда
  async fightAndLoot(st) {
    const f = await this.fight();
    if (!this.grinding) return 'остановлен вручную';
    if (f.result !== 'бой окончен') return 'бой: ' + f.result;
    st.kills++;
    const l = await this.loot();
    if (l.result === 'добыча собрана') st.loots++;
    else if (l.result.startsWith('окно добычи')) return 'окно добычи не закрывается — сумки полные?';
    else if (l.result === 'бой во время сбора') return this.fightAndLoot(st);
    return null;
  },

  async grind(opts = {}) {
    const cfg = Object.assign(this.grindCfg, opts || {});
    this.grinding = true;
    const anchor = this.state(), t0 = Date.now();
    const st = this.grindStats = { kills: 0, loots: 0, rests: 0, wanders: 0, skipped: 0,
                                   anchor: { x: anchor.x, y: anchor.y }, startedAt: t0 };
    const out = r => { this.stop(); return { result: r, stats: st, ...this.state() }; };

    while (this.grinding) {
      const s = this.state();
      if (s.hp === 0) return out('персонаж погиб');
      if (cfg.maxKills && st.kills >= cfg.maxKills) return out('лимит убийств достигнут');
      if (Date.now() - t0 > cfg.maxMinutes * 60000) return out('время вышло');

      if (s.fight) {                                        // на нас напали — сначала отбиться
        const p = await this.fightAndLoot(st); if (p) return out(p); continue;
      }
      if (s.hp / s.hpMax < cfg.restBelow) {
        await this.rest(); st.rests++; continue;
      }
      if (Math.hypot(s.x - anchor.x, s.y - anchor.y) > cfg.radius * 1.5) {   // ушли далеко — назад
        const m = await this.moveTo(anchor.x, anchor.y);
        if (m.result === 'застрял') return out('застрял по пути к центру');
        continue;
      }

      const tgt = await this.findTarget();
      if (!this.grinding) break;
      if (tgt) {
        if (!(await this.approach())) { st.skipped++; await chat('/cleartarget'); continue; }
        const p = await this.fightAndLoot(st); if (p) return out(p);
        continue;
      }

      st.wanders++;                                         // никого — побродить внутри радиуса
      if (st.wanders > 15 && st.kills === 0) return out('мобов поблизости не нашёл');
      const a = Math.random() * 2 * Math.PI, r = cfg.radius * (0.4 + 0.6 * Math.random());
      await this.moveTo(anchor.x + r * Math.cos(a), anchor.y + r * Math.sin(a));
    }
    return out('остановлен вручную');
  },
});
```

## Часть 3. Java

- `GameState` — поля `tLvl`, `tFlags`, `tDist`, объект `grind` (статистика, может быть null).
- Новый инструмент **`grind`** с необязательными параметрами: `radius` (0.5..10), `maxKills` (0..200), `maxMinutes` (1..180), `minLvlDiff`, `maxLvlDiff` (-10..5). Передаёт объект настроек: `bot.start('grind', {…})` (только заданные поля). Описание: «Автоматическая охота вокруг текущей точки: найти подходящего моба, подойти, убить, собрать добычу, отдохнуть, повторить. Работает в фоне. Возвращается по лимиту убийств/времени или при проблеме (смерть, застрял, сумки полные, мобов нет). Во время охоты вызывай wait_job; статистика — в поле grind.»
- `wait_job` без изменений (макс 60 с) — модель вызывает его повторно, пока гринд идёт. Это нормально: ответы маленькие.
- `deploy.sh` после правок.

## Проверка

1. `./deploy.sh` → новая сессия → `get_state`: новые поля `tLvl`, `tFlags`, `tDist` есть. Выдели моба — значения меняются.
2. Встать в месте с несколькими обычными мобами своего уровня.
3. «Начни гринд: радиус 2, максимум 3 убийства» → `grind` → `wait_job`, пока не завершится.
4. Ожидаемо: 3 цикла найти/подойти/бой/лут, при HP < 60% — отдых, результат «лимит убийств достигнут» со статистикой.
5. Проверить `stop` посреди гринда: персонаж должен сразу остановиться, задача — «остановлен вручную».

## Известные ограничения (не чинить сейчас)

- Смерть не обрабатывается (бег к трупу) — гринд просто возвращает «персонаж погиб».
- Tab выбирает только цели перед персонажем — поэтому поворот по кругу.
- `fight()` при смерти цели может сам взять Tab-цель без проверки уровня/элиты — редкий случай, разберём, если всплывёт.
- Продажа хлама — следующий этап (сумки).