# wow-mcp

MCP-сервер (stdio) для управления персонажем в **LastHavens** — клиенте WoW 3.3.5,
скомпилированном в WebAssembly и работающем в Chrome.

Java-приложение подключается к уже запущенному Chrome по CDP, находит вкладку игры,
загружает в неё бота из модулей `src/main/resources/bot/` (по `manifest.txt`) и выполняет
Lua-подготовку из `lua-setup.txt`. Дальше LLM-клиент (OpenCode) вызывает MCP-инструменты,
а вся логика ходьбы, боя, сбора добычи и отдыха остаётся в `bot/*.js` внутри страницы.

## Запуск

Как запускать всё (игра, OpenCode, дашборд) — в [docs/ЗАПУСК.md](docs/ЗАПУСК.md).

## Статус работ

Что сделано и что осталось — в [docs/STATUS.md](docs/STATUS.md).

## Инструменты

| Инструмент | Действие |
|---|---|
| `get_state` | Состояние персонажа: x, y, hpPct, бой, цель, задача, `ready`, `moDead`, `lootN`, питомец, статистика гринда |
| `go_to(x, y, autoLoot=true, useRoads=true)` | Бежать к точке (по графу дорог, если он помогает), по пути отбиваясь от мобов и собирая добычу (фоном) |
| `move_to(x, y, useRoads=true)` | Бежать без боя, остановка при нападении (фоном) |
| `build_roads` | Перестроить граф дорог по записям пути (manual / игрок / бот) |
| `fight` | Драться по реальной готовности умений, реагируя на ошибки «не лицом / далеко» |
| `loot` | Найти труп синтетической мышью и собрать добычу (фоном) |
| `rest` | Отдыхать (лечение/питьё/еда/лечение питомца) до порогов HP/маны (фоном) |
| `grind` | Авто-охота вокруг точки или обход мест (`mode=radius/patrol`): моб → бой → лут → отдых → продажа → повтор (фоном) |
| `look_around` | Найти цель сканом экрана мышью (вся видимая область) (фоном) |
| `record_start` / `record_stop` | Режим обучения: запись пути и убийств в БД |
| `spots` | Места убийств в текущей зоне (из БД) |
| `wait_job(maxSeconds)` | Ждать завершения задачи, опрос раз в секунду. По умолчанию 30 с, максимум 60 с |
| `stop` | Экстренная остановка |
| `spells` | Умения на панелях: spellId, nameHash, клавиша, готовность, дальность |
| `set_rotation(rotationJson)` | Задать ротацию (v2 `spell` или v1 `key`; `maxHpPct`, `maxPetHpPct`, `repeatMs`, `outOfCombatOnly`, `waitAfter`); сохраняется в БД для активного профиля |
| `get_rotation` | Показать текущую ротацию и статистику обучения |
| `set_profile(name)` | Активный профиль (paladin / hunter), хранится в БД |
| `learn_rotation` | Переучить ротацию по сыгранному (spell_cast) |
| `reset_learning(name)` | Удалить данные обучения профиля |
| `bags` | Сумки: свободно/всего, что на продажу, деньги |
| `remember_vendor(name)` / `vendors` | Запомнить торговца там, где стоит игрок / список торговцев зоны |
| `sell_now` | Продать хлам и починиться у ближайшего торговца (фоном) |
| `keep_item(itemId)` | Не продавать предмет (БД + сразу в игру) |
| `hunt(kills, loot, maxDistance)` | «Иди качайся»: лучший кластер мобов под уровень → бежать → grind → следующий (фоном) |
| `remember_place(name)` / `places` | Запомнить текущее место под именем / места зоны с расстоянием |
| `go_place(name)` | Бежать к месту (по записанному маршруту, если он подходит, иначе напрямую) |
| `routes` / `go_route(name, reverse?)` | Список маршрутов / пройти маршрут (имя дают в `record_stop(name)`) |
| `revive` | Воскреснуть: дух → бег к трупу → `RetrieveCorpse` → отдых (фоном); если жив — «персонаж жив» |
| `reload_bot` | Перезагрузить `bot.js` в открытой странице игры (без её перезагрузки) |

Все инструменты возвращают короткую JSON-строку. Координаты — 0..100 (координаты зоны).

Готовность умений и ошибки интерфейса читаются из Lua-таблицы `B`:

| Ячейка | Значение |
|---|---|
| `B[13]` | битовая маска готовых слотов панели 1–9 |
| `B[14]` | код ошибки: 1 — не лицом к цели, 2 — слишком далеко, 9 — другая |
| `B[15]` | время последней ошибки (`GetTime()`) |
| `B[16]` | 1, если под курсором труп |
| `B[17]` | число предметов в открытом окне добычи |

Ротация — приоритетный список слотов панели 1–9 (первый подходящий применяется):

| Поле | Смысл |
|---|---|
| `key` | слот `'1'`..`'9'` |
| `maxHpPct` | применять, только если HP ниже этой доли (0..1) |
| `outOfCombatOnly` | применять, только если бой ещё не начался (открывающий удар) |
| `waitAfter` | `{key, ms}`: не переходить к следующим умениям, пока с `key` прошло меньше `ms` |

По умолчанию:

```json
[{"key":"2","maxHpPct":0.35},
 {"key":"4","maxHpPct":0.35,"waitAfter":{"key":"2","ms":1000}},
 {"key":"3","outOfCombatOnly":true},
 {"key":"1"}]
```

После нажатия `4` бот 3 секунды не двигается по ошибке «слишком далеко», чтобы не сбить лечение.

## Требования

- JDK 21+ (собирается с `--release 21`)
- Maven (или `./mvnw`)
- Chrome с запущенной игрой

## Сборка

```bash
./mvnw clean package
```

Готовый артефакт: `target/wow-mcp.jar`.

## Подключение к игре

Игра должна быть открыта в Chrome, запущенном с отладочным портом. Профиль
`$HOME/chrome-wow` создаётся один раз — в нём нужно войти в игру.

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --remote-debugging-port=9222 \
  --user-data-dir="$HOME/chrome-wow"
```

Затем открой вкладку `https://lasthavens.com/play` и войди персонажем.

Особенности:

- Java **не запускает** Chrome и не скачивает браузеры Playwright — только `connectOverCDP`.
- Подключение к Chrome ленивое: происходит при первом вызове инструмента, а не при старте.
- Все обращения к Playwright выполняются в одном выделенном потоке (Playwright не потокобезопасен).

## Подключение в OpenCode

`~/.config/opencode/opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "wow": {
      "type": "local",
      "command": ["java", "-jar", "/АБСОЛЮТНЫЙ/ПУТЬ/wow-mcp/target/wow-mcp.jar"]
    }
  }
}
```

Если хочешь править модули бота без пересборки jar, добавь свойство `wow.bot-js-dir`
(см. раздел «Живая правка модулей бота»):

```json
{
  "type": "local",
  "command": [
    "java",
    "-Dwow.bot-js-dir=/Users/vadim/Downloads/wow-mcp/src/main/resources/bot",
    "-jar", "/Users/vadim/Downloads/wow-mcp/target/wow-mcp.jar"
  ]
}
```

Проверка:

```bash
opencode mcp list
```

В списке должен появиться `wow` с инструментами `get_state`, `go_to`, `move_to`, `fight`,
`loot`, `rest`, `wait_job`, `stop`, `set_rotation`, `get_rotation`, `reload_bot`.

> После пересборки jar нужно **перезапустить MCP-сервер** (перезапустить OpenCode):
> Java-инструменты и новые строки `lua-setup.txt` читаются при старте процесса.
> Модули `bot/*.js` при заданном `wow.bot-js-dir` можно обновлять через `reload_bot`
> без перезапуска.

## Проверка по ТЗ

1. Запусти Chrome командой выше и войди в игру (профиль `$HOME/chrome-wow`).
2. Открой вкладку `lasthavens.com/play`, персонаж должен быть в мире.
3. Собери проект: `./mvnw package`.
4. Подключи MCP-сервер в `opencode.json` (см. выше) и выполни `opencode mcp list`.
5. В OpenCode: **«Покажи состояние персонажа»** → должен вызваться `get_state`
   и вернуться JSON с координатами, HP и задачей.
6. В OpenCode: **«Пробеги на 2 единицы восточнее и подожди результат»** →
   модель вызовет `get_state`, затем `go_to(x+2, y)` и `wait_job`.
   `wait_job` вернёт финальный отчёт (например, `"result":"дошёл"`).

### Проверка боя и добычи

1. `reload_bot` — Lua-подготовка выполнится заново, включая новые строки.
2. `get_state` — поле `ready` должно быть ненулевым, если на панели 1–9 есть умения.
   Возьми врага в цель: маска изменится (появятся умения, требующие цели).
3. Напади на одиночного моба → `fight` → `wait_job`. Умения должны нажиматься
   по мере реальной готовности, без «пустых» нажатий.
4. Сразу после боя → `loot` → `wait_job`. Ожидаемо: `"result":"добыча собрана"`.
5. `go_to` через место с мобами: бой → автолут → продолжение пути.

### Известные ограничения

- Слоты 1–9 — только первая панель. В стойках воина / формах друида кнопки могут
  переключаться на другие слоты, тогда маска готовности будет неверной.
- Синтетическая мышь не проверялась вживую. Если `loot` всегда возвращает
  «труп не найден под курсором» — проверь, что при наведении реальной мыши на труп
  `get_state` показывает `"moDead": true`, и что `MouseEvent` на `#canvas` двигает курсор игры.

## Конфигурация

`src/main/resources/application.yml`:

```yaml
wow:
  cdp-url: http://localhost:9222
  page-url-contains: lasthavens.com/play
  # bot-js-dir: /абсолютный/путь/к/bot   # каталог с модулями; по умолчанию — classpath
  # lua-dir: /абсолютный/путь/к/lua      # каталог с Lua-модулями; по умолчанию — classpath
  # learn:
  #   disabled-profiles: hunter           # выключить автообучение ротации для профилей
  #   excluded-spell-ids: 883,2641        # доп. заклинания, которые обучение не учит
```

Можно переопределить без пересборки, например:

```bash
java -jar target/wow-mcp.jar --wow.cdp-url=http://localhost:9333
```

### Живая правка модулей бота

Бот собран из модулей `bot/00-core.js`, `bot/10-move.js`, `bot/20-fight.js`, `bot/30-loot.js`.
Порядок склейки задаёт `bot/manifest.txt`, модули соединяются через `;\n` и загружаются
в страницу как один скрипт. Модули расширяют общий объект через `Object.assign(bot, {...})`.

По умолчанию модули берутся из classpath (внутри jar), поэтому после правки нужна
пересборка. Чтобы обойтись без неё, задай абсолютный путь к каталогу модулей:

```bash
java -Dwow.bot-js-dir=/Users/vadim/Downloads/wow-mcp/src/main/resources/bot \
     -jar target/wow-mcp.jar
```

или в `opencode.json` (см. раздел «Подключение в OpenCode»):

```json
"command": ["java", "-Dwow.bot-js-dir=/Users/vadim/Downloads/wow-mcp/src/main/resources/bot",
            "-jar", "/Users/vadim/Downloads/wow-mcp/target/wow-mcp.jar"]
```

Тогда `reload_bot` перечитывает `manifest.txt` и модули с диска при каждом вызове:
правишь `bot/*.js`, вызываешь `reload_bot` — и страницу игры перезагружать не нужно.
Старый бот перед удалением останавливается и отпускает клавиши.

Для stdio-режима критично, чтобы в stdout не попадало ничего, кроме протокола MCP:

- `spring.main.web-application-type: none`, `spring.main.banner-mode: "off"`;
- логирование только в файл `logs/wow-mcp.log` (`logback-spring.xml` без консольного аппендера);
- Playwright создаётся с `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`; браузеры не нужны, только CDP.

## Диагностика

- Логи: `logs/wow-mcp.log`.
- **«Не удалось подключиться к Chrome по CDP»** — Chrome не запущен с
  `--remote-debugging-port=9222` или порт занят/другой.
- **«Вкладка ... не найдена»** — открой `lasthavens.com/play` именно в этом Chrome.
- **«Lua-таблица не найдена: проверь, что персонаж в игре и чат не занят»** — персонаж
  не в мире, открыт чат/окно, либо интерфейс перезагружался; закрой чат и повтори вызов.
- **«Lua-подготовка не уложилась в 20 с (2 попытки)…»** / **«Игра не ответила за 25 с…»** —
  окно Chrome свёрнуто или вкладка заморожена (игра на паузе). Разверни окно игры и
  повтори вызов. Каждая попытка подготовки пишется в лог.
- Первый вызов инструмента может занять несколько секунд: Playwright распаковывает
  встроенный драйвер.
- **Не пересобирай `target/wow-mcp.jar`, пока MCP-сервер запущен.** Spring Boot
  подгружает классы из jar лениво; если файл заменить под работающим процессом, он
  падает с `ClassNotFoundException` (например, `reactor.core.publisher.LambdaMonoSubscriber`)
  и MCP-сервер пропадает. Сначала останови OpenCode, потом собирай.
- **`Unsupported type of argument` в логе** — в `page.evaluate(...)` передали `long`.
  Playwright сериализует только `Integer`/`Double` (для таймаутов есть
  `GameBridge.toPageTimeoutMs`).

## База данных (необязательно)

Настройки — в `application.yml` (секция `wow.db`): `url`, `username`, `password`.
Если `url` пустой или БД недоступна, сервер стартует и работает как раньше (события не сохраняются, в лог WARN).

### События (убийства, нажатия, применения заклинаний)

- Сервер сам подключается к Chrome сразу после старта (без Lua-подготовки), поэтому
  события начинают писаться без первого вызова инструмента.
- Применения заклинаний игроком ловятся по Lua-счётчику `S[3]` (`UNIT_SPELLCAST_SUCCEEDED`)
  и пишутся в `spell_cast`. Действием бота считаются только те применения, что попали в
  гринд/запущенную задачу (`bot.grinding`, `job.status === 'running'`) или в 3 с после
  нажатия бота; `stop()` переводит задачу в `stopped`, чтобы ручные нажатия не терялись.
- Если профиль не задан (`set_profile`), события пишутся под профилем `default` — без
  этого `spell_cast` оставался пустым. `set_profile` сразу сохраняет профиль в БД
  (таблица `active_profile`), поэтому после перезапуска сервера и перезагрузки страницы
  `autoLoadProfile` поднимает его сам; в пределах одной страницы профиль переживает
  `reload_bot` (`window.__wowProfile`).
- Раз в минуту в лог пишется строка
  `события: cast=N, kill=M, вставлено cast=K (key=…, опросов страницы=…, ошибок вставки=…)` —
  по ней видно, доходят ли события из страницы до БД.
- Ошибка одной вставки больше не выключает БД целиком: WARN со стектрейсом, отключение —
  только после 5 ошибок подряд.

## Статистика (дашборд)

Модуль `dashboard/` — отдельное веб-приложение (Spring Boot, порт 8085), которое **только читает** БД `wow_bot`:
убийства бота и игрока, добыча, смерти, продажи, опыт, деньги, запуски задач бота, карта убийств.
Данные пишет wow-mcp: таблицы `mob_kill`, `bot_job`, `game_event` (события `loot`, `lootmiss`, `death`, `sell`, `stat`).

```bash
./mvnw -q -f dashboard/pom.xml package
java -jar dashboard/target/wow-dashboard.jar      # http://localhost:8085
```

БД задаётся переменными `WOW_DB_URL`, `WOW_DB_USER`, `WOW_DB_PASSWORD` (по умолчанию `wow_bot`, пользователь `vadim`).
Данные вашей игры без бота пишутся, только пока запущен сервер wow-mcp: события из страницы вычитывает он.

## Структура

```
src/main/java/com/example/wowmcp/
  WowMcpApplication.java          // точка входа + регистрация @Tool-методов
  config/WowProperties.java       // wow.cdp-url, wow.page-url-contains
  game/GameBridge.java            // фасад: report/start/stop/eval для инструментов
  game/PageConnection.java        // Playwright, CDP, поток, переподключение
  game/BotReadiness.java          // загрузка bot.js и Lua-подготовка
  game/GameSessionSync.java       // профиль и KEEP из БД → игра
  game/BotScriptLoader.java       // сборка bot/*.js по manifest.txt
  game/LuaSources.java            // lua-setup.txt + lua/*.lua, версия
  game/LuaMinifier.java           // сжатие и нарезка Lua для чата
  game/GameState.java             // record под JSON из bot.report()
  db/Database.java                // пул PostgreSQL, флаг «БД доступна», схема (SchemaInitializer)
  db/*Repository.java             // Kill, Path, CastHistory, Profile, Vendor, KeepItem
  db/EventCollector.java          // сбор событий бота раз в 3 с (только лидер, см. LeaderLock)
  tools/BotActionTools.java       // get_state, go_to, move_to, fight, loot, rest, wait_job, stop, reload_bot
  tools/GrindTools.java           // grind, spots
  tools/RecordingTools.java       // record_start, record_stop
  tools/RotationTools.java        // spells, ротация, профили, обучение
  tools/InventoryTools.java       // bags, торговцы, sell_now, keep_item
  tools/ToolSupport.java          // общие помощники; RotationParser, GrindOptions — валидация
dashboard/                        // отдельное приложение статистики (только чтение БД), свой pom.xml
src/main/resources/
  bot/                            // модули бота
    00-core.js                    // window.* хелперы, базовый bot, start/report
    10-move.js                    // moveTo, go
    20-fight.js                   // fight, rest
    30-loot.js                    // loot
    manifest.txt                  // порядок склейки модулей
  lua-setup.txt                   // Lua-команды подготовки, по одной на строку
  application.yml
  logback-spring.xml              // логи только в файл
```
