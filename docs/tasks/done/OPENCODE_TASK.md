# Задание: wow-mcp — MCP-сервер для управления персонажем в LastHavens (WoW 3.3.5 в браузере)

## Контекст (прочитай целиком перед началом)

LastHavens (https://lasthavens.com/play) — клиент WoW 3.3.5, скомпилированный в WebAssembly и работающий в Chrome.
Уже сделан и проверен вручную JS-слой `bot.js` (прилагается). Он работает внутри страницы игры:

- читает состояние персонажа из Lua-таблицы `B` в памяти WASM (`wasmMemory`) — координаты, HP, бой, цель;
- нажимает клавиши через синтетические `KeyboardEvent` на `#canvas`;
- пишет в чат игры через `window.wowKey(code)` (функция самого клиента);
- умеет `moveTo`, `fight`, `rest`, `go` (бег + бой по пути);
- фоновый режим: `bot.start(name, ...args)` сразу возвращает ответ, `bot.report()` отдаёт JSON состояния и задачи.

**Твоя задача — Java-приложение, которое:**
1. подключается к уже запущенному Chrome по CDP и находит вкладку игры;
2. само подготавливает бота (загружает `bot.js`, вводит Lua-команды, вызывает `bot.find()`);
3. отдаёт это как MCP-инструменты (stdio), чтобы LLM-клиент (OpenCode) мог управлять персонажем.

Реальное время (циклы 100 мс, бой) остаётся в `bot.js`. Java НЕ должна дёргать клавиши в цикле — только вызывать функции `bot.*`.

## Стек

- Java 21, Maven
- Spring Boot 3.x + Spring AI MCP Server (stdio transport) — возьми актуальную стабильную версию Spring AI 1.x и соответствующий стартер MCP-сервера, проверь по документации
- Playwright for Java (`com.microsoft.playwright:playwright`) — только `connectOverCDP`, браузер НЕ запускаем сами
- Lombok — по желанию

## Структура

```
wow-mcp/
  pom.xml
  src/main/java/.../WowMcpApplication.java
  src/main/java/.../game/GameBridge.java      // CDP, evaluate, подготовка бота
  src/main/java/.../game/GameState.java       // record под JSON из bot.report()
  src/main/java/.../tools/WowTools.java       // @Tool методы
  src/main/resources/bot.js                   // из приложенного файла, БЕЗ последней строки "await bot.find();"
  src/main/resources/lua-setup.txt            // Lua-команды, по одной на строку
  src/main/resources/application.yml
```

## Критично для stdio MCP

- В stdout НЕ должно попадать ничего, кроме MCP-протокола. Отключить баннер, логи только в файл (`logs/wow-mcp.log`).
- `spring.main.web-application-type: none`, `spring.main.banner-mode: off`.
- Playwright тоже может писать в stdout при первом скачивании драйвера — проверь и при необходимости перенаправь/отключи.

## GameBridge

Конфиг (`application.yml`):
```yaml
wow:
  cdp-url: http://localhost:9222
  page-url-contains: lasthavens.com/play
```

Методы:
- `connect()` — `playwright.chromium().connectOverCDP(cdpUrl)`, найти вкладку, у которой `url()` содержит `page-url-contains`. Нет вкладки — понятная ошибка.
- `Object eval(String js)` — `page.evaluate(js)`; переподключиться, если страница закрыта/соединение упало (1 повтор).
- `Object eval(String fn, Object arg)` — `page.evaluate(fn, arg)` для передачи строк без ручного экранирования.
- `ensureReady()` — вызывается перед каждым инструментом:
    1. если `typeof bot === 'undefined'` → загрузить `bot.js` из ресурсов через `page.evaluate` (оберни содержимое в `() => { ... }` или используй `addScriptTag` — проверь, что `window.bot` появился);
    2. если `bot.addr === null` → выполнить подготовку Lua:
        - `document.getElementById('canvas').focus()`
        - для каждой строки `lua-setup.txt`: `eval("t => chat(t)", line)`, пауза 700 мс
        - `await bot.find()` (evaluate вернёт Promise — Playwright его дождётся)
        - проверить `bot.addr !== null`, иначе ошибка «Lua-таблица не найдена: проверь, что персонаж в игре и чат не занят»
    3. если `bot.state().time` не меняется 1 секунду (интерфейс игры перезагружался `/reload`) → повторить шаг 2.

## lua-setup.txt (ровно эти строки)

```
/run B={777001.5,777002.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0} SetMapToCurrentZone()
/run function U() local x,y=GetPlayerMapPosition("player") B[3]=x B[4]=y B[5]=GetPlayerFacing() B[6]=UnitHealth("player") B[7]=UnitHealthMax("player") B[8]=UnitAffectingCombat("player") and 1 or 0 B[9]=GetTime() end
/run function V() B[10]=UnitExists("target") and 1 or 0 B[11]=UnitHealth("target") B[12]=UnitCanAttack("player","target") and 1 or 0 end CreateFrame("Frame"):SetScript("OnUpdate",function() U() V() if W then W() end end)
```

Таблица на 20 ячеек — с запасом: на следующих этапах будут добавляться функции `W()` и т.п., которые пишут в `B[13..20]`, без пересоздания таблицы. Каждая строка ≤ 255 символов (лимит чата игры) — не превышай.

## MCP-инструменты — ЭТАП 1 (только это, больше ничего)

Все возвращают короткий JSON-строкой. Описания (`description`) — по-русски, понятные модели.

| Инструмент | Что делает | JS |
|---|---|---|
| `get_state` | Состояние персонажа: x, y (координаты зоны 0–100), hpPct, fight, target, текущая задача | `bot.report()` |
| `go_to(x, y)` | Бежать к точке, по пути отбиваясь от мобов. Не ждёт окончания | `bot.start('go', x, y)` |
| `move_to(x, y)` | Бежать без боя, остановится при нападении | `bot.start('moveTo', x, y)` |
| `fight` | Драться с текущими врагами | `bot.start('fight')` |
| `rest` | Отдыхать до 80% HP | `bot.start('rest')` |
| `wait_job(maxSeconds)` | Ждать (опрашивая `bot.report()` раз в 1 с) пока `job.status != 'running'` или истечёт время (макс 60 с). Вернуть финальный report | цикл в Java |
| `stop` | Экстренная остановка | `bot.stop()` |

`wait_job` важен: модель вызывает `go_to`, потом `wait_job(60)` — вместо десятков опросов. Это главная экономия токенов.

Валидация: x, y в диапазоне 0..100, иначе ошибка с объяснением.

## Проверка (сделай и опиши в README)

1. Chrome запускается так (macOS), в этом профиле один раз войти в игру:
   ```
   /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="$HOME/chrome-wow"
   ```
2. Персонаж в игре, открыта вкладка lasthavens.com/play.
3. `mvn package`, затем подключить в OpenCode (`~/.config/opencode/opencode.json`):
   ```json
   {
     "$schema": "https://opencode.ai/config.json",
     "mcp": {
       "wow": { "type": "local", "command": ["java", "-jar", "/АБСОЛЮТНЫЙ/ПУТЬ/wow-mcp/target/wow-mcp.jar"] }
     }
   }
   ```
4. `opencode mcp list` → wow подключён.
5. В OpenCode: «Покажи состояние персонажа» → вызывается `get_state`.
6. «Пробеги на 2 единицы восточнее и подожди результат» → `get_state`, `go_to(x+2, y)`, `wait_job`.

## Чего НЕ делать на этапе 1

- Не трогать квесты, сумки, вендоров, маршруты — это следующие этапы.
- Не делать скриншоты и не парсить картинки.
- Не писать свою логику ходьбы/боя в Java — она уже в `bot.js`.
- Не запускать Chrome из Java.

## Следующие этапы (для понимания архитектуры, не реализовывать сейчас)

- Этап 2: сумки — Lua пишет число свободных слотов в `B[13]`, инструменты `bag_free_slots`, `sell_junk` (у торговца).
- Этап 3: чтение строк из Lua (названия квестов) + `list_quests` с цветом сложности и координатами POI.
- Этап 4: запись маршрутов по дорогам (`record_route`, `follow_route`), хранение маршрутов/вендоров в файле или БД.
- Этап 5: автономный цикл «взять квест → выполнить → сдать → сумки», LLM только на развилках.