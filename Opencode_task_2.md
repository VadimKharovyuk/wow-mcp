# Задание 2: умный бой + сбор добычи (wow-mcp)

Проект: `/Users/vadim/Downloads/wow-mcp`. Этап 1 работает (get_state, go_to, wait_job, reload_bot и т.д.).
Делаем: бой по реальной готовности умений (а не по таймерам), реакцию на ошибки «цель не перед вами / слишком далеко», и подбор добычи с трупа.

Перед кодом — коротко покажи план изменений.

---

## Часть 1. Lua: новые данные из игры

Добавь в КОНЕЦ `src/main/resources/lua-setup.txt` три строки (каждая ≤ 255 символов — проверь длину).
Функция `W()` уже вызывается каждый кадр из существующей строки 3 (`if W then W() end`), таблица `B` уже на 20 ячеек.

```
/run function W() local m=0 for i=1,9 do local s,d=GetActionCooldown(i) if IsUsableAction(i) and d==0 and IsActionInRange(i)~=0 then m=m+2^(i-1) end end B[13]=m B[16]=UnitIsDead("mouseover") and 1 or 0 B[17]=GetNumLootItems() end
/run function E(m) B[14]=(m==ERR_BADATTACKFACING or m==SPELL_FAILED_UNIT_NOT_INFRONT) and 1 or (m==ERR_BADATTACKPOS or m==SPELL_FAILED_OUT_OF_RANGE or m==ERR_OUT_OF_RANGE) and 2 or 9 B[15]=GetTime() end
/run local f=CreateFrame("Frame") f:RegisterEvent("UI_ERROR_MESSAGE") f:SetScript("OnEvent",function(_,_,m) E(m) end) SetCVar("autoLootDefault","1") SetCVar("autoInteract","1")
```

Что это даёт:

| Ячейка | Значение |
|---|---|
| `B[13]` | битовая маска готовности слотов панели 1..9: бит (i-1) = 1, если умение в слоте i можно применить прямо сейчас (хватает маны/ярости, нет перезарядки и ГКД, цель в радиусе) |
| `B[14]` | код последней ошибки интерфейса: 1 = не лицом к цели, 2 = слишком далеко, 9 = другая |
| `B[15]` | игровое время этой ошибки (`GetTime()`) |
| `B[16]` | 1, если под курсором мыши мёртвый юнит (труп) |
| `B[17]` | число предметов в открытом окне добычи (0 = окно закрыто) |

`autoLootDefault=1` — добыча забирается сразу при клике по трупу. `autoInteract=1` (click-to-move) — правый клик по далёкому трупу заставляет персонажа подбежать к нему.

---

## Часть 2. bot.js

Файл: `src/main/resources/bot.js`. Изменения:

### 2.1 `state()` — новые поля
```js
ready: g(12), err: g(13), errTime: +g(14).toFixed(2), moDead: g(15) === 1, lootN: g(16)
```

### 2.2 Общий хелпер `hold` (вынести из moveTo в метод бота, moveTo перевести на него)
```js
async hold(keys, ms) {
  keys = [].concat(keys);
  keys.forEach(k => this.key(k, true)); await sleep(ms); keys.forEach(k => this.key(k, false));
},
```

### 2.3 Ротация — приоритетный список слотов
Формат: `{ key: '3', maxHpPct: 0.4 }` — `maxHpPct` необязателен (использовать, только если HP ниже).
Перезарядку больше не задаём — её знает маска `ready`.
```js
rotation: [
  { key: '3', maxHpPct: 0.4 },
  { key: '2' },
  { key: '1' },
],
```

### 2.4 Новый `fight()`
```js
async fight({ fleeAt = 0.2, timeout = 90000 } = {}) {
  this.running = true;
  const t0 = Date.now();
  let lastPress = 0, handledErr = 0, turns = 0;
  while (this.running) {
    const s = this.state(), hpPct = s.hp / s.hpMax;
    if (!s.fight && (!s.target || s.targetHp === 0)) return { result: 'бой окончен', ...s };
    if (hpPct < fleeAt) { this.stop(); return { result: 'мало HP — нужен выбор', ...s }; }
    if (Date.now() - t0 > timeout) { this.stop(); return { result: 'бой слишком долгий', ...s }; }

    if (!s.target || !s.enemy || s.targetHp === 0) { await this.tap('TAB'); await sleep(300); continue; }

    // реакция на свежую ошибку, случившуюся после нашего нажатия
    if (s.errTime > lastPress && s.errTime > handledErr) {
      handledErr = s.errTime;
      if (s.err === 1 && turns < 4) { await this.hold('A', 450); turns++; continue; }  // ~90° поворот
      if (s.err === 2) { await this.hold('W', 500); continue; }                       // подойти ближе
    }

    const isReady = k => (s.ready >> (+k - 1)) & 1;
    const r = this.rotation.find(r => isReady(r.key) && (!r.maxHpPct || hpPct < r.maxHpPct));
    if (r) { await this.tap(r.key); lastPress = s.time; turns = 0; }
    await sleep(250);
  }
  return { result: 'остановлен вручную', ...this.state() };
},
```

### 2.5 Новый `loot()` — поиск трупа мышью
Принцип: двигаем синтетическую мышь по сетке точек экрана (от центра-низа, где обычно лежит труп), Lua сообщает `moDead`, когда курсор над трупом → правый клик. Благодаря autoInteract персонаж сам подбежит, autoLoot заберёт добычу.
```js
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
},
```

### 2.6 `go()` — после боя собирать добычу
Добавь параметр: `async go(x, y, { autoLoot = true } = {})`. После `fight()` с результатом `'бой окончен'` — если `autoLoot`, вызвать `await this.loot()` (результат лута не прерывает путь, кроме `'бой во время сбора'` — тогда снова `fight()`).

---

## Часть 3. Java (MCP-инструменты)

- `GameState` — добавить поля `ready`, `err`, `errTime`, `moDead`, `lootN` (в `job` тоже могут прийти).
- Новый инструмент **`loot`** — `bot.start('loot')`. Описание: «Найти ближайший труп на экране и собрать добычу. Вызывать после боя.»
- Новый инструмент **`set_rotation(rotationJson)`** — проверяет, что это JSON-массив объектов с полем `key` ('1'..'9') и необязательным `maxHpPct` (0..1), затем `bot.rotation = <json>`. Описание: «Задать приоритет умений: список слотов панели 1–9 от самого важного к основному удару.»
- Новый инструмент **`get_rotation`** — `JSON.stringify(bot.rotation)`.
- `go_to` — добавить необязательный параметр `autoLoot` (по умолчанию true).

---

## Проверка (по шагам, после каждого — показать результат)

1. `reload_bot` (Lua-setup выполнится заново с новыми строками).
2. `get_state` — у поля `ready` должно быть ненулевое значение (если на панели 1–9 есть умения). Выбери врага в цель — значение изменится (появятся умения, требующие цели).
3. Напасть на одиночного моба → `fight` → `wait_job`. Ожидаемо: умения жмутся по мере готовности, без «пустых» нажатий.
4. Сразу после боя → `loot` → `wait_job`. Ожидаемо: `добыча собрана`.
5. `go_to` через место с мобами → бой → автолут → продолжение пути.

## Известные ограничения (не чинить сейчас, просто знать)

- Слоты 1–9 = первая панель. У воинов в стойках / друидов в формах кнопки 1–9 переключаются на другие слоты — тогда маска будет неверной. Решим позже, если понадобится.
- Синтетическая мышь не проверялась вживую. Если `loot` всегда возвращает «труп не найден» — первым делом проверить, что при наведении реального курсора на труп `moDead` становится `true` (через `get_state`), и что `MouseEvent` на `#canvas` вообще двигает курсор игры.