# Задание 4б: гринд по точкам из базы («где мобы обычно стоят»)

Проект: `/Users/vadim/Downloads/wow-mcp`. Правила `AGENTS.md` действуют.
Перед кодом — покажи план. Проверка: `./mvnw -q package -DskipTests`, `node --check`. `deploy.sh` не запускать.

## Зачем
Сейчас, когда рядом нет моба, гринд крутится на месте (Tab по кругу + скан экрана ~15 с) и бредёт в случайную точку.
В таблице `mob_kill` уже есть координаты десятков убийств (игрока и бота) с зоной и уровнем моба.
Делаем: гринд идёт **к ближайшему «тёплому» месту**, где мобы уже встречались и успели появиться снова,
и только там осматривается.

---

## Часть 1. Java — точки охоты

### 1.1 Запрос (DbService)
```sql
SELECT round(avg(x)::numeric, 2)  AS x,
       round(avg(y)::numeric, 2)  AS y,
       count(*)                   AS kills,
       round(avg(mob_level))      AS lvl,
       max(killed_at)             AS last_kill
FROM mob_kill
WHERE zone = :zone
  AND killed_at > now() - interval '30 days'
  AND (mob_level IS NULL OR mob_level BETWEEN :minLvl AND :maxLvl)
GROUP BY floor(x / 1.0), floor(y / 1.0)          -- сетка 1×1 единица карты
HAVING count(*) >= 2                             -- одиночные случайные убийства не считаем
ORDER BY kills DESC
LIMIT 40;
```
`minLvl/maxLvl` = уровень игрока (`pLvl`) + `minLvlDiff/maxLvlDiff` из параметров гринда.

### 1.2 Инструмент `grind` — новые параметры
- `useSpots` (bool, по умолчанию `true`)
- `respawnSec` (60..1800, по умолчанию 240) — через сколько секунд после визита место снова считается «тёплым»
- Перед `bot.start('grind', opts)`: прочитать `get_state` (zone, pLvl), выполнить запрос 1.1 и передать точки
  в `opts.spots = [{x, y, kills, lvl}]` (только если БД доступна и `useSpots`; иначе без точек — старое поведение).
- В ответе инструмента: сколько точек передано (`spots: N`).

### 1.3 Инструмент `spots` — добавить фильтр по уровню
Те же условия, что 1.1 (зона текущая, уровень ±), чтобы игрок видел ровно то, что получит гринд.

## Часть 2. JS — `40-grind.js`

### 2.1 Настройки
```js
grindCfg: { ...существующие..., respawnSec: 240 },
```
`grind(opts)`: `this.grindSpots = (opts.spots || []).map(p => ({ ...p, visitedAt: 0, empty: 0 }))`.
Статистика: `st.spotVisits = 0, st.spotHits = 0`.

### 2.2 Выбор места
```js
// лучшее «тёплое» место внутри радиуса охоты: много убийств, близко, давно не были, не пустовало
pickSpot(s, anchor, cfg) {
  const now = Date.now();
  let best = null, bestScore = 0;
  for (const p of this.grindSpots || []) {
    if (Math.hypot(p.x - anchor.x, p.y - anchor.y) > cfg.radius) continue;       // вне зоны охоты
    if (p.visitedAt && now - p.visitedAt < cfg.respawnSec * 1000) continue;      // ещё не респавнулось
    const dist = Math.hypot(p.x - s.x, p.y - s.y) + 0.3;
    const score = p.kills * Math.pow(0.5, p.empty) / dist;                        // пустые места теряют вес
    if (score > bestScore) { bestScore = score; best = p; }
  }
  return best;
},
```

### 2.3 Поиск цели: быстрый и полный
- `findTarget({ quick })`: при `quick === true` — только Tab, **1 оборот** (6 поворотов по 60°), без `lookAround`.
  Без `quick` — как сейчас (Tab + `lookAround`).

### 2.4 Цикл `grind()` — изменить блок «никого не нашли»
Порядок внутри итерации (бой/отдых/возврат к центру — без изменений):
```
const hasSpots = (this.grindSpots || []).length > 0;
tgt = await this.findTarget({ quick: hasSpots });   // при наличии точек не тратим время на полный скан
if (tgt) → подойти → бой → лут → continue

if (hasSpots) {
  const spot = this.pickSpot(s, anchor, cfg);
  if (spot) {
    st.spotVisits++;
    const m = await this.moveTo(spot.x, spot.y, { tol: 0.4 });
    if (m.result === 'бой') continue;                 // напали по дороге — цикл сам отобьётся
    spot.visitedAt = Date.now();
    const t2 = await this.findTarget({ quick: false }); // на месте — полный осмотр
    if (t2) { st.spotHits++; → подойти → бой → лут; }
    else spot.empty++;
    continue;
  }
}
// точек нет или все «остывают» — старое поведение: случайная прогулка внутри радиуса
```
- Условие выхода «мобов поблизости не нашёл» (`wanders > 15 && kills === 0`) оставить, но считать и визиты пустых мест.

### 2.5 Статистика в report
`grind: { ..., spotVisits, spotHits, spots: grindSpots.length }`.

## Часть 3. Описание инструмента для модели
`grind`: «Если в базе есть места прошлых убийств в этой зоне — бот ходит по ним (быстрее), иначе ищет вокруг.
Параметр radius ограничивает зону охоты вокруг точки старта. respawnSec — через сколько секунд возвращаться на место.»

## Проверка (делает игрок)
1. Перезапуск OpenCode со службой → в мире → `reload_bot`.
2. `spots` → список мест в текущей зоне (x, y, kills, lvl).
3. Встать в центр района, где уже качался → «Начни гринд: радиус 5, максимум 10 убийств».
   Ожидаемо: меньше кружения на месте, бот идёт к местам из `spots`; в итоге `spotVisits`, `spotHits` > 0.
4. `useSpots: false` → старое поведение (для сравнения времени на 10 убийств).