# Задание 4а: «глаза» (скан экрана) + запись убийств и маршрутов в PostgreSQL

Проект: `/Users/vadim/Downloads/wow-mcp`. Работают: moveTo, fight, loot, rest, grind (Tab-поиск), reload_bot.
Проблема: бот видит мобов только через Tab (впереди, ~30–40 м). Делаем:
1. **Скан экрана мышью** — находит живых врагов во всей видимой области (как loot находит трупы).
2. **Счётчик убийств из игры** + **запись убийств и пути в PostgreSQL** (и когда играет бот, и когда играет человек — «режим обучения»).

Гринд по точкам из базы — следующее задание (4б), сейчас не делать.
Перед кодом — коротко покажи план. `./deploy.sh` сам не запускай (он закрывает OpenCode).

---

## Часть 1. Lua (`lua-setup.txt`)

Добавить ПЕРЕД строкой-обёрткой `W` три строки и дописать вызов `M()` в обёртку.
Каждая строка ≤ 255 символов — **посчитай и выведи длины**.

```
/run function M() local m="mouseover" B[22]=(UnitExists(m) and UnitCanAttack("player",m) and not UnitIsDead(m)) and 1 or 0 B[23]=UnitLevel(m)-UnitLevel("player") B[25]=GetCurrentMapContinent()*100+GetCurrentMapZone() B[26]=UnitLevel("player") end
/run function K(_,e,_,s,g) if e=="ZONE_CHANGED_NEW_AREA" then SetMapToCurrentZone() elseif s=="PARTY_KILL" and g==UnitGUID("player") then B[24]=B[24]+1 end end
/run local f=CreateFrame("Frame") f:RegisterEvent("ZONE_CHANGED_NEW_AREA") f:RegisterEvent("COMBAT_LOG_EVENT_UNFILTERED") f:SetScript("OnEvent",K)
```
Обёртка: `/run local w=W W=function() w() X() Y() Z() M() end`

Если первая строка (M) длиннее 255 — вынеси `B[25]=...` и `B[26]=...` в отдельную функцию `N()` и вызывай её в обёртке.

| Ячейка | Поле state() | Смысл |
|---|---|---|
| B[22] | `moAlive` (=== 1) | под курсором живой враг, которого можно атаковать |
| B[23] | `moLvl` | уровень моба под курсором минус уровень игрока |
| B[24] | `kills` | счётчик убийств игрока (событие PARTY_KILL из боевого лога) — растёт, только пока Lua-подготовка жива |
| B[25] | `zone` | номер зоны: континент*100 + зона (для разделения точек по зонам) |
| B[26] | `pLvl` | уровень игрока |

`ZONE_CHANGED_NEW_AREA` → `SetMapToCurrentZone()`: координаты не обнулятся после перехода в другую зону.

## Часть 2. bot.js

### 2.1 `00-core.js`
- state(): `moAlive: g(21) === 1, moLvl: g(22), kills: g(23), zone: g(24), pLvl: g(25)`.
- Общий хелпер мыши (перенести из `30-loot.js`, loot перевести на него):
  ```js
  mouse(fx, fy, type = 'mousemove', button = 0) {
    const c = CANVAS(), r = c.getBoundingClientRect();
    c.dispatchEvent(new MouseEvent(type, {
      clientX: r.left + fx * r.width, clientY: r.top + fy * r.height,
      button, buttons: type === 'mousedown' ? (button === 2 ? 2 : 1) : 0, bubbles: true }));
  },
  async click(fx, fy, button = 0) {
    this.mouse(fx, fy, 'mousemove'); await sleep(40);
    this.mouse(fx, fy, 'mousedown', button); await sleep(60); this.mouse(fx, fy, 'mouseup', button);
  },
  ```
- `dispose()`: `clearInterval(this.trackInt)` — вызывать в GameBridge.reloadBot перед `delete window.bot` (иначе старый трекер продолжит жить).

### 2.2 Новый модуль `50-scan.js` (+ manifest.txt)
```js
Object.assign(bot, {
  // Скан видимой области: курсор идёт по сетке от центра к краям; Lua сообщает «под курсором живой враг»
  async scanScreen({ minLvlDiff = -5, maxLvlDiff = 2 } = {}) {
    const pts = [];
    for (let fy = 0.30; fy <= 0.72; fy += 0.07)
      for (let fx = 0.06; fx <= 0.94; fx += 0.06) pts.push([fx, fy]);
    pts.sort((a, b) => (Math.abs(a[0] - 0.5) + Math.abs(a[1] - 0.5) * 0.5)
                     - (Math.abs(b[0] - 0.5) + Math.abs(b[1] - 0.5) * 0.5));
    for (const [fx, fy] of pts) {
      if (this.job.status === 'running' && !this.grinding && this.job.name !== 'lookAround') break;
      this.mouse(fx, fy); await sleep(45);
      const s = this.state();
      if (!s.moAlive || s.moLvl < minLvlDiff || s.moLvl > maxLvlDiff) continue;
      await this.click(fx, fy, 0);             // левый клик = выделить цель
      await sleep(200);
      const t = this.state();
      if (!this.targetProblem(t)) return { found: true, point: [fx, fy], ...t };
    }
    return { found: false };
  },

  // Осмотреться: скан, поворот ~90°, 4 раза
  async lookAround(opts = {}) {
    for (let i = 0; i < 4; i++) {
      const r = await this.scanScreen(opts);
      if (r.found) return { result: 'цель найдена', ...r };
      if (this.state().fight) return { result: 'бой', ...this.state() };
      await this.hold('A', 500); await sleep(150);
    }
    return { result: 'никого не видно', ...this.state() };
  },
});
```

### 2.3 `40-grind.js` — использовать скан
В `findTarget()`: сначала текущий Tab-поиск (быстрый, но сократить до 2 оборотов вместо 6), если не нашёл → `await this.lookAround({ minLvlDiff: cfg.minLvlDiff, maxLvlDiff: cfg.maxLvlDiff })`, если `'цель найдена'` → вернуть state. Иначе null (→ wander, как сейчас).

### 2.4 Новый модуль `60-track.js` (+ manifest.txt) — запись событий
Работает постоянно (и при игре бота, и при игре человека). События копятся в памяти страницы, Java забирает их пачками.
```js
Object.assign(bot, {
  events: [], lastKills: null, lastTLvl: null, recording: false, recSession: null, lastPathPt: null,

  trackTick() {
    if (this.addr === null) return;
    const s = this.state();
    if (s.target && s.enemy && s.targetHp > 0) this.lastTLvl = s.tLvl;
    if (this.lastKills === null || s.kills < this.lastKills) this.lastKills = s.kills;   // сброс после /reload
    while (s.kills > this.lastKills) {
      this.lastKills++;
      this.events.push({ type: 'kill', zone: s.zone, x: s.x, y: s.y,
        mobLevel: this.lastTLvl === null ? null : s.pLvl + this.lastTLvl, playerLevel: s.pLvl,
        source: this.grinding || this.job.status === 'running' ? 'bot' : 'player', at: Date.now() });
    }
    if (this.recording && (s.x || s.y)) {
      const p = this.lastPathPt;
      if (!p || p.zone !== s.zone || Math.hypot(s.x - p.x, s.y - p.y) >= 0.3) {
        this.lastPathPt = { zone: s.zone, x: s.x, y: s.y };
        this.events.push({ type: 'path', zone: s.zone, x: s.x, y: s.y, session: this.recSession, at: Date.now() });
      }
    }
    if (this.events.length > 5000) this.events.splice(0, this.events.length - 5000);
  },
  startTracker() { if (!this.trackInt) this.trackInt = setInterval(() => { try { this.trackTick(); } catch (e) {} }, 500); },
  drainEvents() { const e = this.events; this.events = []; return JSON.stringify(e); },
});
bot.startTracker();
```

## Часть 3. Java

### 3.1 PostgreSQL — НЕОБЯЗАТЕЛЬНЫЙ
- Зависимости: `spring-boot-starter-jdbc` + драйвер `org.postgresql:postgresql`.
- Настройки ТОЛЬКО из переменных окружения: `WOW_DB_URL`, `WOW_DB_USER`, `WOW_DB_PASSWORD`. Никаких паролей в yml/git.
- **Если переменные не заданы или база недоступна — сервер должен стартовать и работать как раньше** (события просто не сохраняются, в лог WARN). Важно: MCP-сервер не должен падать или долго стартовать из-за базы. Поэтому:
  - отключить автоконфигурацию DataSource при отсутствии `WOW_DB_URL` (условный бин), без Flyway на старте;
  - схему создавать при первом успешном подключении: `CREATE TABLE IF NOT EXISTS`.

```sql
CREATE TABLE IF NOT EXISTS mob_kill (
  id           bigserial PRIMARY KEY,
  zone         int         NOT NULL,
  x            real        NOT NULL,
  y            real        NOT NULL,
  mob_level    int,
  player_level int,
  source       varchar(10) NOT NULL,          -- 'player' | 'bot'
  killed_at    timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS mob_kill_zone_idx ON mob_kill (zone);

CREATE TABLE IF NOT EXISTS path_point (
  id          bigserial PRIMARY KEY,
  session_id  varchar(40),
  zone        int         NOT NULL,
  x           real        NOT NULL,
  y           real        NOT NULL,
  recorded_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS path_point_session_idx ON path_point (session_id);
```

### 3.2 Сборщик событий
- `@EnableScheduling`, компонент `EventCollector`: раз в 3 с, **только если GameBridge уже подключён и бот загружен** (не вызывать ensureReady/Lua-подготовку и не подключаться к Chrome сам!), вызвать `bot.drainEvents()` с коротким таймаутом (2 с), распарсить, batch-insert в нужные таблицы.
- Добавь в GameBridge метод вида `Optional<String> evalIfReady(String js, long timeoutMs)`.
- Если базы нет — события всё равно забирать (чтобы не копились), просто считать и писать в лог.

### 3.3 Новые MCP-инструменты
| Инструмент | Что делает |
|---|---|
| `look_around` | `bot.start('lookAround')` — найти цель сканом экрана (для теста «глаз») |
| `record_start` | `bot.recording = true; bot.recSession = '<uuid>'` — режим обучения: человек играет, бот пишет путь и убийства |
| `record_stop` | `bot.recording = false`; вернуть сводку сессии: точек пути, убийств (из БД, если есть) |
| `spots` | Что бот знает о текущей зоне (зона из `get_state`): убийства сгруппированы по сетке 1×1 единица карты — центр (avg x/y), количество, средний уровень моба, последнее убийство. Топ-30 по количеству. Без БД — понятное сообщение «база не настроена». |

- `GameState`: поля `moAlive`, `moLvl`, `kills`, `zone`, `pLvl`.
- GameBridge.reloadBot: перед `delete window.bot` вызвать `window.bot.dispose && window.bot.dispose()`.

### 3.4 Конфиг OpenCode
В README описать, как передать переменные окружения серверу через `opencode.json` (поле `environment` у MCP-сервера `wow`).

## Проверка
1. `./deploy.sh` (делает человек) → новая сессия → `get_state`: есть `moAlive`, `moLvl`, `kills`, `zone`, `pLvl`.
2. Навести реальный курсор на живого моба → `get_state`: `moAlive: true`.
3. `look_around` → `wait_job`: «цель найдена», моб выделен.
4. Убить моба вручную → `get_state`: `kills` увеличился на 1.
5. С базой: `record_start` → побегать и убить пару мобов вручную → `record_stop` → сводка; `spots` → точки.
6. `grind` (радиус 3, 3 убийства) — Tab, затем скан; убийства бота тоже попадают в `spots` с source = 'bot'.

## Ограничения (не чинить сейчас)
- Скан не видит мобов за спиной без поворота и за препятствиями.
- Левый клик по UI-элементам безвреден, но если курсор попадёт на кнопку панели — может нажать её. Если заметим — сузим область скана.
- Номер зоны — числовой (без названия); названия добавим вместе с чтением строк.