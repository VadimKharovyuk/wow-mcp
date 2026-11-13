# Задание 4в: бот сам учится ротации у игрока (профили персонажей)

Делать ПОСЛЕ задания 4а (нужны DbService, EventCollector, 60-track.js).
Перед кодом — коротко покажи план. `./deploy.sh` сам не запускай.

## Идея
Игрок (опытный, 15 лет в WoW) играет сам — бот незаметно записывает **каждое его нажатие умения + ситуацию**
и **сам** строит ротацию и применяет её. Никаких предложений и подтверждений: выученная ротация
сразу становится рабочей и сохраняется в профиль персонажа. Посмотреть, что выучено, — `get_rotation`.

Нажатия игрока отличаются от нажатий бота: у реальных событий `isTrusted === true`, у синтетических — false.

---

## Часть 1. Lua
Добавить перед строкой-обёрткой `W`:
```
/run function P() B[27]=UnitHealthMax("target") end
```
и вызвать в обёртке: `/run local w=W W=function() w() X() Y() Z() M() P() end`.
state(): `targetHpMax: g(26)`, и вычисляемое `targetHpPct: g(26) > 0 ? +(g(10) / g(26)).toFixed(2) : 0`.

## Часть 2. JS — запись нажатий (в `60-track.js`)

```js
// ловим только НАСТОЯЩИЕ нажатия цифр игроком
installKeyLogger() {
  if (this.keyLogger) return;
  this.keyLogger = e => {
    if (!e.isTrusted || e.repeat) return;
    const m = /^Digit([0-9])$/.exec(e.code); if (!m) return;
    const active = document.activeElement;
    if (active && active.id === 'wow-text') return;          // игрок печатает в чате — это не умение
    if (this.addr === null || !this.profile) return;
    const s = this.state(), now = Date.now();
    if (s.fight && !this.fightStartedAt) this.fightStartedAt = now;
    if (!s.fight) this.fightStartedAt = null;
    this.events.push({ type: 'key', profile: this.profile, key: m[1],
      hp: +(s.hp / s.hpMax).toFixed(2), mana: s.manaPct, ready: s.ready, fight: s.fight,
      target: s.target && s.enemy, targetHpPct: s.targetHpPct,
      sinceFightMs: this.fightStartedAt ? now - this.fightStartedAt : null,
      prevKey: this.lastHumanKey ? this.lastHumanKey.key : null,
      prevKeyMs: this.lastHumanKey ? now - this.lastHumanKey.at : null, at: now });
    this.lastHumanKey = { key: m[1], at: now };
  };
  document.addEventListener('keydown', this.keyLogger, true);
},
```
- Вызвать `bot.installKeyLogger()` рядом со `startTracker()`.
- В `dispose()` снять слушатель (`removeEventListener`).
- Поле `bot.profile` (строка, по умолчанию null — без профиля нажатия не пишутся).

## Часть 3. БД

```sql
CREATE TABLE IF NOT EXISTS key_press (
  id bigserial PRIMARY KEY,
  profile varchar(40) NOT NULL,
  key char(1) NOT NULL,
  hp real, mana real, ready int, fight boolean, target boolean, target_hp_pct real,
  since_fight_ms int, prev_key char(1), prev_key_ms int,
  pressed_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS key_press_profile_idx ON key_press (profile, pressed_at);

CREATE TABLE IF NOT EXISTS rotation_profile (
  profile varchar(40) PRIMARY KEY,
  rotation_json text NOT NULL,
  samples int NOT NULL,
  learned_at timestamptz NOT NULL,
  last_used_at timestamptz NOT NULL      -- последний активный профиль грузится автоматически
);
```
EventCollector: события `type: 'key'` → `key_press`.

## Часть 4. Java — RotationLearner (простая, объяснимая статистика, без ML)

Вход: нажатия профиля за последние 14 дней (только клавиши '1'..'9'; '0' — питьё, его ведёт rest()).

1. **Клавиши**: учитывать клавишу, только если у неё ≥ 5 нажатий.
2. **Открывающее умение**: ≥ 80% нажатий клавиши при `fight = false` → `outOfCombatOnly: true`.
3. **Аварийное умение (по HP)**: пусть `p90` — 90-й перцентиль `hp` у нажатий этой клавиши в бою,
   `med` — медиана `hp` у всех нажатий в бою. Если `p90 < 0.6` и `med > 0.7` →
   `maxHpPct = round(p90 + 0.05, 2)`.
4. **Связка «после X»**: если ≥ 70% нажатий клавиши имеют `prev_key = X` и `prev_key_ms ≤ 3000` →
   `waitAfter: { key: X, ms: медиана prev_key_ms, округлить до 100 }`.
5. **Приоритет**: для каждого нажатия клавиши K посмотреть маску `ready` в момент нажатия: каждая
   другая учитываемая клавиша J, готовая в тот момент (бит J-1), — это «K предпочли J» (wins[K][J]++).
   Порядок — по Копленду: K выше J, если wins[K][J] > wins[J][K]; очко = число клавиш, которых K «побеждает»;
   при равенстве — по частоте нажатий.
   Считать порядок **отдельно**: сначала аварийные (с maxHpPct) — по нажатиям при низком HP,
   затем открывающие, затем остальные — по нажатиям при нормальном HP.
6. Итоговая ротация = аварийные → открывающие → остальные, в формате, который уже понимает fight():
   `[{key, maxHpPct?, outOfCombatOnly?, waitAfter?}, ...]`.
7. **Минимум данных**: учиться, только если в профиле ≥ 3 боёв (смен `fight` false→true по нажатиям
   с `since_fight_ms` около 0, или просто ≥ 60 нажатий в бою). Иначе — оставить текущую ротацию.

**Автообучение**: после каждой вставки в EventCollector, если с прошлого обучения профиля накопилось
≥ 40 новых нажатий — переучить, сохранить в `rotation_profile` и сразу применить в странице
(`bot.rotation = ...`), если этот профиль активен. В лог — INFO с итоговой ротацией.

## Часть 5. MCP-инструменты

| Инструмент | Что делает |
|---|---|
| `set_profile(name)` | `bot.profile = name`; загрузить сохранённую ротацию профиля (если есть) в `bot.rotation`; отметить `last_used_at`. |
| `get_rotation` | Текущая ротация + по каждой клавише: число нажатий и откуда правило (например «HP < 40% в 9 из 10 нажатий»). |
| `learn_rotation` | Принудительно переучить профиль сейчас и применить. |
| `reset_learning(name)` | Удалить нажатия и выученную ротацию профиля (с подтверждением в описании: «необратимо»). |

**Автозагрузка**: в `ensureReady()` после успешной Lua-подготовки — если в БД есть профиль с последним
`last_used_at`, выставить `bot.profile` и его ротацию. Тогда после `reload_bot` / перезапуска ротация
не сбрасывается на значение по умолчанию.

## Проверка
1. `./deploy.sh` → новая сессия → `set_profile("paladin")`.
2. Игрок дерётся сам 10–15 боёв (обычные мобы + пара опасных, чтобы были ситуации с низким HP).
3. `get_rotation` → видно выученную ротацию и статистику по клавишам.
4. `reload_bot` → `get_rotation`: ротация paladin сохранилась (из БД).
5. `grind` — бот бьёт по выученной ротации.
6. Нажатия самого бота в `key_press` не попадают (проверить SQL: число записей не растёт во время grind).

## Ограничения
- Учитываются только нажатия клавиш, не клики мышью по панели.
- Если игрок сменил кнопки местами — `reset_learning` и заново.