# Задание 5: продажа хлама у торговца (+ ремонт)

Проект: `/Users/vadim/Downloads/wow-mcp`. Правила `AGENTS.md` действуют.
Перед кодом — покажи план. Проверка: `./mvnw -q package -DskipTests`, `node --check`. `deploy.sh` не запускать.
Lua пишем в `src/main/resources/lua/` (загрузчик кусками; **только ASCII, без символа `|`**, комментарии допустимы — их вырезает загрузчик).

## Что продаём (правило игрока)
- **Серое (quality 0)** — всё, у чего есть цена продажи.
- **Белое (quality 1)** — только «хлам»: нельзя надеть (`equipLoc == ""`), класс предмета «Разное»,
  есть цена продажи.
- **Никогда**: цена продажи 0 (так защищён Камень возвращения), класс «Квест», «Расходуемые»
  (еда/питьё), «Хозяйственные товары», «Рецепты», «Сумки», надеваемые вещи; предметы из списка KEEP.
- Класс сравнивать **не по русскому тексту**, а через `GetAuctionItemClasses()` (локализованные имена в фиксированном порядке
  3.3.5: 1 Оружие, 2 Доспехи, 3 Сумки, 4 Расходуемые, 5 Символы, 6 Хоз. товары, 7 Боеприпасы, 8 Колчаны,
  9 Рецепты, 10 Самоцветы, 11 Разное, 12 Задания). `GetItemInfo(link)` → name, link, quality, iLevel, reqLevel,
  class, subclass, maxStack, equipLoc, texture, **sellPrice** (11-е значение).

---

## Часть 1. Lua — `lua/bags.lua`: таблица `I` (метки `777005.5`, `777006.5`)
```
I[1], I[2] — метки
I[3]  свободных слотов (сумки 0..4, только обычные: GetContainerNumFreeSlots(bag) с bagType == 0)
I[4]  всего слотов
I[5]  GetTime() — «живость» таблицы (как B[9] / S[5])
I[6]  окно торговца открыто (1/0) — события MERCHANT_SHOW / MERCHANT_CLOSED
I[7]  сколько предметов осталось продать (по правилу выше)
I[8]  счётчик проданных за всё время
I[9]  стоимость ремонта (медь), I[10] — деньги игрока (медь, GetMoney())
I[11] mouseover — это текущая цель: UnitExists("mouseover") and UnitIsUnit("mouseover","target") → 1/0
```
- Обновление I[3], I[4], I[7], I[10], I[11] — в OnUpdate раз в 0.25 с (pcall).
- `KEEP = KEEP or {}` — глобальная таблица itemId → true (не продавать).
- **Продажа при MERCHANT_SHOW**: очередь предметов по правилу; продавать по одному
  `UseContainerItem(bag, slot)` с паузой 0.3 с (через OnUpdate), I[8]++ на каждый.
  После очереди: если `CanMerchantRepair()` и `GetRepairAllCost() > 0` и хватает денег — `RepairAllItems()`.
  Затем `CloseMerchant()`.
- Всё в pcall, ошибки — `print("LUA ERR", err)`.

## Часть 2. JS — `70-vendor.js` (+ manifest)
- `find()` ищет и таблицу `I`; `bags()` → `{ free, total, merchantOpen, toSell, sold, repairCost, money, moIsTarget }`.
- `async sellAt(vendor)`, где `vendor = { x, y, name }`:
    1. `moveTo(vendor.x, vendor.y, { tol: 0.5 })` (если бой — `fight()` + `loot()`, затем продолжить).
    2. `await chat('/target ' + vendor.name)`; подождать до 2 с, пока `state().target` станет true. Нет — результат «торговец не найден по имени».
    3. Найти торговца на экране: скан мышью (как в `scanScreen`), пока `bags().moIsTarget` не станет 1;
       затем **правый клик** в этой точке (click-to-move подведёт персонажа и откроет окно).
    4. Ждать `merchantOpen === 1` до 10 с; затем ждать `toSell === 0` и `merchantOpen === 0` до 30 с.
    5. Вернуть `{ result: 'продано', sold: <разница счётчика>, freeAfter, moneyDelta }`.
- В `grind()`: если `bags().free <= cfg.minFreeSlots` (по умолчанию 2) и в `opts.vendor` есть торговец —
  `sellAt(vendor)`, затем `moveTo(anchor)` и продолжить. Если торговца нет — завершить гринд с результатом
  «сумки полные — торговец не задан».
- **Важно**: имя торговца приходит из Java и печатается в чат через `chat()`. Проверь, что кириллица
  печатается корректно (`wowKey(codePoint)`); если нет — вернуть понятную ошибку.

## Часть 3. Java + БД
```sql
CREATE TABLE IF NOT EXISTS vendor (
  id bigserial PRIMARY KEY,
  zone int NOT NULL, x real NOT NULL, y real NOT NULL,
  name varchar(80) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
```
MCP-инструменты:
| Инструмент | Что делает |
|---|---|
| `remember_vendor(name)` | Запомнить торговца в ТЕКУЩЕЙ точке игрока (zone/x/y из get_state). Игрок стоит рядом с NPC. |
| `vendors` | Список торговцев текущей зоны. |
| `sell_now` | Ближайший торговец текущей зоны → `bot.start('sellAt', vendor)`. |
| `bags` | Свободно/всего слотов, сколько к продаже, деньги. |
| `keep_item(itemId)` | Добавить предмет в KEEP (через chat `/run KEEP[<id>]=true`). |
- `grind`: ближайший торговец зоны передаётся в `opts.vendor`; параметр `minFreeSlots` (0..10, по умолчанию 2).

## Проверка (делает игрок)
1. Сборка → перезапуск OpenCode со службой → в мире → `reload_bot` (в чате нет `LUA ERR`).
2. «Сколько места в сумках?» (`bags`) — совпадает с игрой.
3. Встать у торговца → «Запомни торговца, его зовут <Имя>».
4. Отойти на 20–30 м → «Продай хлам» (`sell_now`): бот подходит, открывает окно, продаёт серое и белый «Разное»,
   чинится, закрывает. Камень возвращения, еда/питьё и надетое — на месте.
5. Гринд с `minFreeSlots: 20` (чтобы сработало сразу) → бот уходит продавать и возвращается.