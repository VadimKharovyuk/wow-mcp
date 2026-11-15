# Задание 4г: умения по заклинаниям, а не по слотам (+ клавиши из игры)

Проект: `/Users/vadim/Downloads/wow-mcp`. Правила `AGENTS.md` действуют (браузер/процессы/deploy не трогать).
Перед кодом — покажи план. Проверка: `./mvnw -q package -DskipTests`, `node --check`.

## Зачем
Сейчас бот мыслит **номерами слотов 1–9** и жмёт **цифры**. Это ломается, если игрок:
- запускает умения мышью или клавишами-буквами (F, Shift+E…), ставит их на дополнительные панели;
- переставляет кнопки местами;
- использует слоты 10–12 (`0`, `-`, `=`).

Делаем: бот оперирует **идентификатором заклинания (spellId)**. Игра сама сообщает, на каком слоте оно лежит,
готово ли, и **какая клавиша** к нему привязана. Обучение пишет **применённые заклинания** (событие игры),
независимо от способа запуска.

Факты, проверенные в игре (WoW 3.3.5, этот сервер):
- `GetActionInfo(slot)` для заклинаний возвращает `"spell", <индекс в книге заклинаний>` (НЕ spellId!).
  Название: `GetSpellName(index, "spell")`.
- spellId получаем из ссылки: `GetSpellLink(index, "spell")` или `GetSpellLink(name)` → `...spell:20271...`.
- Иконка: `GetActionTexture(slot)`.

---

## Часть 0. Загрузчик Lua-файлов (вместо однострочников ≤255)

Lua-логика выросла — однострочники неудобны. Сделай загрузку **обычных многострочных .lua файлов** через чат:
- Файлы: `src/main/resources/lua/*.lua` + `lua/manifest.txt` (порядок). Существующие строки `lua-setup.txt`
  можно оставить как есть (они работают) — новое пишем в `lua/`.
- GameBridge: склеить файлы, разбить на куски ≤ 200 символов и печатать в чат:
  `/run _L=(_L or "")..[==[<кусок>]==]` …, затем `/run local f,e=loadstring(_L) _L=nil if f then f() else print("LUA ERR",e) end`.
- **Нельзя использовать символ `|` в Lua-исходниках** (чат WoW экранирует его). Для `||` — `or`, для литерала — `string.char(124)`.
  Проверять это при сборке (тест): если в lua-файле есть `|` — ошибка.
- `luaVer` считать по хешу ВСЕХ Lua-источников (lua-setup.txt + lua/*.lua).
- Режим разработки: `wow.bot-js-dir` уже есть; добавь аналогичный `wow.lua-dir` (читать lua/ с диска) — тогда правки Lua
  применяются через `reload_bot` без пересборки. Добавь его и в README.

## Часть 1. Lua: таблица заклинаний `S` (`lua/spells.lua`)

Вторая таблица с метками `777003.5`, `777004.5` (JS ищет её так же, как `B`).
Раскладка (все значения — числа):
```
S[1], S[2]  — метки 777003.5, 777004.5
S[3]        — счётчик применений заклинаний игроком (растёт на каждое UNIT_SPELLCAST_SUCCEEDED)
S[4]        — spellId последнего применённого заклинания
S[5]        — GetTime() последнего обновления (для поиска «живой» таблицы, как B[9])
S[6]        — число записей N (≤ 24)
S[7..]      — записи по 6 чисел: spellId, slot, keyCode, mods, ready, inRange
```
- Каждые 0.5 с (через OnUpdate с накоплением elapsed) — пересканировать панели и заполнить записи.
  Слоты для скана и их команды привязки клавиш:
  | слоты | команда для GetBindingKey |
  |---|---|
  | 1–12 | `ACTIONBUTTON`..i |
  | 61–72 | `MULTIACTIONBAR1BUTTON`..(s-60) (нижняя левая) |
  | 49–60 | `MULTIACTIONBAR2BUTTON`..(s-48) (нижняя правая) |
  | 25–36 | `MULTIACTIONBAR3BUTTON`..(s-24) (правая) |
  | 37–48 | `MULTIACTIONBAR4BUTTON`..(s-36) (правая 2) |
  Если одно заклинание на нескольких слотах — брать тот, у которого есть клавиша.
- **Клавиша → числа**: `GetBindingKey(cmd)` возвращает строку вида `"F"`, `"SHIFT-E"`, `"CTRL-3"`, `"-"`, `"="`.
  `mods`: 1 = SHIFT, 2 = CTRL, 4 = ALT (сумма). `keyCode` для основы: буква/цифра → `string.byte(upper)`,
  `-` → 189, `=` → 187, `F1..F12` → 111+n; прочее (кнопки мыши, numpad) → 0 (не поддерживается, бот такое не нажмёт).
- `ready` = 1, если `IsUsableAction(slot)` и `GetActionCooldown(slot)` с duration == 0, иначе 0.
  `inRange` = `IsActionInRange(slot)`: 1 / 0, nil → -1 (у умения нет дальности).
- **События**: `UNIT_SPELLCAST_SUCCEEDED` (unit == "player"): spellId по `GetSpellLink(spellName)` → `S[4]`, `S[3]=S[3]+1`.
  Обернуть в pcall — ошибка Lua не должна ронять OnUpdate.

## Часть 2. JS

### 2.1 `00-core.js`
- `find()` обобщить: `findTable(markA, markB, timeIndex)` → находит B (как сейчас) и S (`777003.5/777004.5`, время в S[5]).
  `bot.find()` ищет обе; `bot.saddr/sstride` для S.
- `spells()` → массив записей `{ spellId, slot, keyCode, mods, ready, inRange }`.
- `castInfo()` → `{ count: S[3], lastSpell: S[4] }`.
- `pressSpell(spellId)`: найти запись; если нет или `keyCode === 0` → вернуть false.
  Нажать с модификаторами: сначала keydown модификаторов (Shift: code `ShiftLeft`, keyCode 16; Ctrl: `ControlLeft`, 17;
  Alt: `AltLeft`, 18), затем клавиша с флагами `shiftKey/ctrlKey/altKey`, затем keyup в обратном порядке.
  `code` для клавиши: буквы → `Key<X>`, цифры → `Digit<n>`, `-` → `Minus`, `=` → `Equal`, F-клавиши → `F<n>`.
  Отмечать `bot.lastBotPressAt = Date.now()`.

### 2.2 Ротация v2 (`20-fight.js`)
Новый формат элемента (старый с `key` продолжает работать):
```js
{ spell: 853, maxHpPct: 0.35 }                                  // Молот правосудия
{ spell: 635, maxHpPct: 0.35, waitAfter: { spell: 853, ms: 1000 } }
{ spell: 62124, outOfCombatOnly: true }
{ spell: 20271 }
```
- Готовность: `ready === 1 && inRange !== 0` из `spells()`.
- `waitAfter.spell`: время последнего нажатия этого заклинания ботом (`bot.lastSpellAt[spellId]`).
- Нажатие: `pressSpell(spell)`. Если заклинания нет на панелях — пропустить и записать предупреждение в `bot.warnings`.
- `rest()` и `grindCfg`: `healSpell`, `drinkKey` (питьё — предмет, оставить по клавише `0`) вместо `healKey`.

### 2.3 Запись применений (`60-track.js`)
- В `trackTick()` (интервал уменьшить до 150 мс): если `castInfo().count` вырос — событие
  `{ type: 'cast', profile, spellId: lastSpell, hp, mana, fight, target, targetHpPct,
     readySpells: [spellId готовых в этот момент], sinceFightMs, prevSpell, prevSpellMs, at }`.
- **Не писать применения самого бота**: если `bot.job.status === 'running'` или `bot.grinding`, или с
  `lastBotPressAt` < 3000 мс — событие помечать `source: 'bot'` и в обучение не брать (можно не писать вовсе).
- keydown-логгер (`installKeyLogger`) — удалить.

## Часть 3. БД и обучение
```sql
CREATE TABLE IF NOT EXISTS spell_cast (
  id bigserial PRIMARY KEY,
  profile varchar(40) NOT NULL,
  spell_id int NOT NULL,
  hp real, mana real, fight boolean, target boolean, target_hp_pct real,
  ready_spells int[],                 -- какие заклинания были готовы в момент применения
  since_fight_ms int, prev_spell int, prev_spell_ms int,
  cast_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS spell_cast_profile_idx ON spell_cast (profile, cast_at);
```
- `RotationLearner` перевести на `spell_cast`: тот же алгоритм (≥5 применений, открывающие, аварийные по p90 HP,
  связки «после X» по `prev_spell`, приоритет по Копленду — «победил» = применил X, когда Y был в `ready_spells`).
  Результат — ротация v2 со `spell`.
- `key_press` больше не наполняется (таблицу не удалять).
- `rotation_profile.rotation_json` хранит ротацию v2.

## Часть 4. MCP-инструменты
- `spells` — что бот видит на панелях: spellId, слот, клавиша (`"Shift+E"`), готово ли, в радиусе ли.
  (Названия заклинаний — строки, их пока не читаем; spellId достаточно. Для справки: игрок может узнать название
  через `/run print(GetSpellInfo(<id>))`.)
- `set_rotation` — принимать и v1 (`key`), и v2 (`spell`).
- `get_rotation` — показывать spellId + текущий слот + клавишу для каждого правила.

## Проверка (делает игрок)
1. `./mvnw -q package -DskipTests` → перезапуск OpenCode со службой → в мире → `reload_bot`.
   В чате игры не должно быть `LUA ERR`.
2. `spells` → список с клавишами (`1`, `3`, `4`, `7`, `8`, `9`, `=`). Переназначить одно умение на букву (например `F`) →
   `spells` через пару секунд показывает `F`.
3. Бой вручную (мышью или клавишами) → `psql`: `select spell_id, count(*) from spell_cast group by spell_id;` — растёт.
4. `fight` с ротацией v2 — бот жмёт умение по новой клавише (`F`).
5. Ни одного применения бота в `spell_cast` во время `fight`/`grind`.