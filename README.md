# wow-mcp

MCP-сервер (stdio) для управления персонажем в **LastHavens** — клиенте WoW 3.3.5,
скомпилированном в WebAssembly и работающем в Chrome.

Java-приложение подключается к уже запущенному Chrome по CDP, находит вкладку игры,
загружает в неё `bot.js` и выполняет Lua-подготовку из `lua-setup.txt`. Дальше LLM-клиент
(OpenCode) вызывает MCP-инструменты, а вся логика ходьбы, боя и отдыха остаётся в `bot.js`
внутри страницы.

## ЭТАП 1 — что реализовано

| Инструмент | Действие |
|---|---|
| `get_state` | Состояние персонажа: x, y, hpPct, бой, цель, текущая задача |
| `go_to(x, y)` | Бежать к точке, по пути отбиваясь от мобов (фоном) |
| `move_to(x, y)` | Бежать без боя, остановка при нападении (фоном) |
| `fight` | Драться с текущими врагами (фоном) |
| `rest` | Отдыхать до 80% HP (фоном) |
| `wait_job(maxSeconds)` | Ждать завершения задачи, опрос раз в секунду. По умолчанию 30 с, максимум 60 с |
| `stop` | Экстренная остановка |

Все инструменты возвращают короткую JSON-строку. Координаты — 0..100 (координаты зоны).

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

Проверка:

```bash
opencode mcp list
```

В списке должен появиться `wow` с инструментами `get_state`, `go_to`, `move_to`, `fight`,
`rest`, `wait_job`, `stop`.

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

## Конфигурация

`src/main/resources/application.yml`:

```yaml
wow:
  cdp-url: http://localhost:9222
  page-url-contains: lasthavens.com/play
```

Можно переопределить без пересборки, например:

```bash
java -jar target/wow-mcp.jar --wow.cdp-url=http://localhost:9333
```

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
- Первый вызов инструмента может занять несколько секунд: Playwright распаковывает
  встроенный драйвер.

## Структура

```
src/main/java/com/example/wowmcp/
  WowMcpApplication.java          // точка входа + регистрация @Tool-методов
  config/WowProperties.java       // wow.cdp-url, wow.page-url-contains
  game/GameBridge.java            // CDP, evaluate, подготовка bot.js и Lua
  game/GameState.java             // record под JSON из bot.report()
  tools/WowTools.java             // MCP-инструменты этапа 1
src/main/resources/
  bot.js                          // логика бота (без финального await bot.find())
  lua-setup.txt                   // Lua-команды подготовки, по одной на строку
  application.yml
  logback-spring.xml              // логи только в файл
```
