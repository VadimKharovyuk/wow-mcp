# Статус работ

Обновлено 15.11. Задание-источник: `CLAUDE_TASK.md`. Задания для OpenCode лежат в `docs/tasks/`
(выполненные — в `docs/tasks/done/`).

## Сделано

### Архитектура Java (рефакторинг, поведение не менялось)
- `GameBridge` (1088 строк) → фасад + `PageConnection` (Playwright/CDP, поток), `BotReadiness` (загрузка бота,
  Lua-подготовка), `GameSessionSync` (профиль и KEEP из БД → игра), `BotScriptLoader`, `LuaSources`,
  `LuaMinifier`, `ResourceReader`.
- `WowTools` (915 строк) → `BotActionTools`, `GrindTools`, `RecordingTools`, `RotationTools`, `InventoryTools`
  + `ToolSupport`, `RotationParser`, `GrindOptions`. Те же 25 MCP-инструментов.
- `DbService` (545 строк) → `Database`, `SchemaInitializer` и репозитории `Kill`, `Path`, `CastHistory`,
  `Profile`, `Vendor`, `KeepItem`.
- Общие помощники: `util.Values`, `learn.Stats`.

### Баги из CLAUDE_TASK.md, раздел 3
| № | Баг | Статус |
|---|---|---|
| 1 | Автообучение учит мусор и затирает правила | Исправлено: `LearnPolicy` (исключения, сохранение ручных правил `maxHpPct`/`maxPetHpPct`/`repeatMs`/`key`, `wow.learn.disabled-profiles`). **Не сделано:** убрать закреплённую ротацию hunter из `20-fight.js` — только после проверки обучения в игре |
| 2 | `spell_cast.spell_id` всегда NULL | Исправлено: `SpellIdResolver` + дозаполнение старых строк |
| 3 | `set_rotation` не пишет в БД, описание устарело | Исправлено; `RotationParser` принимает `maxPetHpPct` и `repeatMs` |
| 4 | Клавиша «=» не принимается | Частично: `which` в событиях + предупреждение в `warnings`. Причина не найдена, проверять в игре |
| 5 | `remember_vendor`: «Утрок» → «урок» | В Java имя не искажается (`trim`), вероятно ошибка модели; проверить в игре |
| 6 | Job остаётся в `running` | Исправлено: id задачи, `start()` не оставляет `running` при исключении |
| 7 | Гринд не отдыхает из-за питомца | Исправлено в коде (`petNeedsRest`), проверять в игре |
| 8 | Tab не находит нейтральных зверей | Не сделано (нужен скан мышью на бегу) |

### Ревью ручных правок бота (раздел 2)
Найдено и исправлено: «стоп» не прерывал объезд препятствий, `turnTo`/`retreat` и начало боя после остановки
гринда; гонка двух задач после `stop`; движение прерывало чтение лечения v2/питомца; Tab на бегу сбивал цель
в бою. Подробности — в коммите `f9a1ee2`.
Замечено, не менялось: фильтр `ok` в `findTable` не сравнивает время (`indexOf` на новом массиве всегда -1).

## Задания для OpenCode (порядок из CLAUDE_TASK.md, раздел 4)
| № | Файл | Статус |
|---|---|---|
| 6 | `OPENCODE_TASK_6_COMBAT.md` | Код готов (все 17 пунктов, см. `docs/plans/TASK6_PLAN.md`), **ждёт проверки в игре** и перезапуска сервера (изменён `lua-setup.txt`) |
| 7 | `docs/tasks/OPENCODE_TASK_7_DEATH.md` | Код готов, **ждёт проверки в игре** и перезапуска сервера (новый `lua/death.lua`): таблица D, `80-death.js` (`revive`), `autoRevive` в гринде, защита «3 смерти за 10 мин», инструмент `revive`, поля `dead`/`ghost` в `get_state` |
| 8 | `OPENCODE_TASK_8_HUNTER.md` | Код готов, **ждёт проверки в игре**. Части 2, 3, 4б были сделаны вручную; добавлено: вызов питомца (`ensurePet`, клавиша `petCallKey`), пауза гринда при мёртвом питомце (статус `paused`), боеприпасы (`D[10]`, остановка при 0, предупреждение < 200), подход стрелка до дальности выстрела, поля `petExists`/`petHpPct`/`petDead`/`ammo` в `get_state`. Отход по спецификации (замедление, ожидание питомца 1.5 с) не менялся: действует ручная логика «отходить, когда моба держит питомец» |
| 11 | `OPENCODE_TASK_11_PLACES.md` | Код готов, **ждёт проверки в игре**: таблицы `place`/`route` (создаются при старте, места-«семена» добавлены), `PlaceTools` (`remember_place`, `places`, `go_place`, `routes`, `go_route`), `record_stop(name)`, `RoutePlanner` (прореживание, направление «от меня», старт с ближайшей точки), `90-route.js` (`goRoute`, пропуск застрявших точек), поле `route` в `get_state` |
| 9 | `docs/tasks/OPENCODE_TASK_9_HUNT.md` | Код готов, **ждёт проверки в игре и БД**: `hunt` (`HuntTools`, `HuntRepository`, таблица `hunt_cluster_stat`), `95-hunt.js`, `findByName` (`/target имя`) в `grind` и `hunt`, события `hunt` → `EventCollector`, поле `hunt` и `grind.byName` в `get_state`. Нужны `mob_spawn`, `zone_map`, `mob_cluster` в БД (импортирует игрок; запросы написаны по описанию в задании, на реальной БД не запускались). Файл `Opencode_task_9_hunt.md` в корне — по ошибке копия задания 8, актуален `docs/tasks/OPENCODE_TASK_9_HUNT.md` |
| 10 | `OPENCODE_TASK_10_ROADS.md` | Код готов, **ждёт проверки в игре и БД**: постоянная запись пути (`path_point.source` = player/bot/manual, `trackAutoPath` в `60-track.js`), таблицы `road_node`/`road_edge`, `RoadGraphBuilder` (шаг 0.8, слияние 0.6, бот без остановок > 3 с), A* (`RoadGraph`), `RoadService` (маршрут, обучение, чистка точек бота > 7 дней), `build_roads`, `go_to`/`move_to` с `useRoads`, `go_place` через `Navigator`. Ограничения: при застревании на ребре бот идёт прямо к цели (повторного A* без ребра нет); `route(from,to)` — внутренний метод `RoadService.plan`, не MCP-инструмент |

## Статистика и дашборд (15.11)
- wow-mcp пишет в БД: `bot_job` (запуски задач со сводкой), `game_event` (jsonb: `loot` по LOOT_OPENED — бот и игрок,
  `lootmiss`, `death`, `sell`, `stat` — снимки опыта/уровня/денег), `mob_kill.job_uid`. Lua: `D[11]` счётчик окон добычи,
  `D[12]`/`D[13]` опыт.
- `dashboard/` — отдельный Spring Boot (порт 8085, только чтение): API `/api/summary|timeline|progress|jobs|spots` и страница
  с графиками (Chart.js). SQL проверен на тестовых данных; на вашей базе и с реальными событиями не запускался.
- Ограничение: данные вашей игры без бота пишутся, только пока работает сервер wow-mcp. Полная запись потребует отдельного
  сборщика (не сделано).

## Как проверять изменения бота
JS: `node --check` для каждого `bot/*.js`, затем в игре «перезагрузи бота». Java/Lua: `./mvnw -q package`,
перезапуск сервера (`deploy.sh` запускает только игрок). Версия Lua — хеш `lua-setup.txt` + `lua/*.lua`,
поднимается автоматически.
