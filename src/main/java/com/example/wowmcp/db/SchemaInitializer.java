package com.example.wowmcp.db;

import org.springframework.jdbc.core.JdbcTemplate;

/** Создание таблиц. Миграции только добавлением: CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS. */
final class SchemaInitializer {

    private SchemaInitializer() {
    }

    static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mob_kill (
                  id           bigserial PRIMARY KEY,
                  zone         int         NOT NULL,
                  x            real        NOT NULL,
                  y            real        NOT NULL,
                  mob_level    int,
                  player_level int,
                  source       varchar(10) NOT NULL,
                  killed_at    timestamptz NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS mob_kill_zone_idx ON mob_kill (zone)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS path_point (
                  id          bigserial PRIMARY KEY,
                  session_id  varchar(40),
                  zone        int         NOT NULL,
                  x           real        NOT NULL,
                  y           real        NOT NULL,
                  recorded_at timestamptz NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS path_point_session_idx ON path_point (session_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS key_press (
                  id            bigserial PRIMARY KEY,
                  profile       varchar(40) NOT NULL,
                  key           char(1)     NOT NULL,
                  hp            real,
                  mana          real,
                  ready         int,
                  fight         boolean,
                  target        boolean,
                  target_hp_pct real,
                  since_fight_ms int,
                  prev_key      char(1),
                  prev_key_ms   int,
                  pressed_at    timestamptz NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS key_press_profile_idx ON key_press (profile, pressed_at)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rotation_profile (
                  profile       varchar(40) PRIMARY KEY,
                  rotation_json text        NOT NULL,
                  samples       int         NOT NULL,
                  learned_at    timestamptz NOT NULL,
                  last_used_at  timestamptz NOT NULL,
                  stats_json    text
                )""");
        // активный профиль переживает перезапуск сервера и перезагрузку страницы;
        // отдельная таблица, чтобы не путать его с выученной ротацией (rotation_profile).
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS active_profile (
                  id         int         PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                  profile    varchar(40) NOT NULL,
                  updated_at timestamptz NOT NULL DEFAULT now()
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS spell_cast (
                  id           bigserial PRIMARY KEY,
                  profile      varchar(40) NOT NULL,
                  name_hash    int         NOT NULL,
                  spell_id     int,
                  hp           real,
                  mana         real,
                  fight        boolean,
                  target       boolean,
                  target_hp_pct real,
                  ready_spells int[],
                  since_fight_ms int,
                  prev_spell   int,
                  prev_spell_ms int,
                  cast_at      timestamptz NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS spell_cast_profile_idx ON spell_cast (profile, cast_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS spell_cast_name_hash_idx ON spell_cast (name_hash)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vendor (
                  id bigserial PRIMARY KEY,
                  zone int NOT NULL,
                  x real NOT NULL,
                  y real NOT NULL,
                  name varchar(80) NOT NULL,
                  facing real,
                  created_at timestamptz NOT NULL DEFAULT now()
                )""");
        // старые БД: колонка facing появилась позже
        jdbc.execute("ALTER TABLE vendor ADD COLUMN IF NOT EXISTS facing real");
        jdbc.execute("CREATE INDEX IF NOT EXISTS vendor_zone_idx ON vendor (zone)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS keep_item (
                  item_id int PRIMARY KEY
                )""");
        // именованные места и маршруты (задание 11); route мог быть создан вручную раньше — только добавляем
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS place (
                  name       text PRIMARY KEY,
                  zone       int         NOT NULL,
                  x          real        NOT NULL,
                  y          real        NOT NULL,
                  note       text,
                  created_at timestamptz NOT NULL DEFAULT now()
                )""");
        jdbc.execute("""
                INSERT INTO place (name, zone, x, y) VALUES
                  ('перекресток', 117, 51.8, 30.3),
                  ('лагерь таурахо', 117, 45.0, 58.9),
                  ('гарпии', 117, 40.9, 17.0)
                ON CONFLICT (name) DO NOTHING""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS route (
                  name       text PRIMARY KEY,
                  session_id varchar(40) NOT NULL
                )""");
        jdbc.execute("ALTER TABLE route ADD COLUMN IF NOT EXISTS created_at timestamptz DEFAULT now()");
        // результаты реальных посещений кластеров охоты (задание 9). mob_spawn, zone_map и mob_cluster
        // импортирует игрок; здесь только своя статистика. cx/cy — numeric(8,2): view может отдавать real или int.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS hunt_cluster_stat (
                  zone_id     int          NOT NULL,
                  cx          numeric(8,2) NOT NULL,
                  cy          numeric(8,2) NOT NULL,
                  visits      int          NOT NULL DEFAULT 0,
                  found       int          NOT NULL DEFAULT 0,
                  kills       int          NOT NULL DEFAULT 0,
                  last_visit  timestamptz,
                  empty_until timestamptz,
                  note        text,
                  PRIMARY KEY (zone_id, cx, cy)
                )""");
        // граф дорог (задание 10): источник точек пути и сам граф. Старые записи — 'manual' (record_start).
        jdbc.execute("ALTER TABLE path_point ADD COLUMN IF NOT EXISTS source varchar(10) NOT NULL DEFAULT 'manual'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS path_point_source_idx ON path_point (source, recorded_at)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS road_node (
                  id   bigint PRIMARY KEY,
                  zone int  NOT NULL,
                  x    real NOT NULL,
                  y    real NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS road_node_zone_idx ON road_node (zone)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS road_edge (
                  a       bigint  NOT NULL,
                  b       bigint  NOT NULL,
                  cost    real    NOT NULL,
                  fails   int     NOT NULL DEFAULT 0,
                  ok      int     NOT NULL DEFAULT 0,
                  learned boolean NOT NULL DEFAULT false,
                  PRIMARY KEY (a, b)
                )""");
        // статистика игры для дашборда (модуль dashboard/ читает эти таблицы только на чтение)
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bot_job (
                  uid        text PRIMARY KEY,
                  name       text        NOT NULL,
                  started_at timestamptz NOT NULL,
                  ended_at   timestamptz,
                  status     text,
                  result     text,
                  data       jsonb
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS bot_job_started_idx ON bot_job (started_at)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS game_event (
                  id      bigserial PRIMARY KEY,
                  ts      timestamptz NOT NULL,
                  type    text        NOT NULL,
                  source  text,
                  job_uid text,
                  zone    int,
                  x       real,
                  y       real,
                  data    jsonb
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS game_event_type_ts_idx ON game_event (type, ts)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bot_log (
                  id       bigserial PRIMARY KEY,
                  ts       timestamptz NOT NULL,
                  profile  text,
                  level    text        NOT NULL,
                  category text        NOT NULL,
                  message  text,
                  zone     int,
                  x        real,
                  y        real,
                  job_uid  text,
                  data     jsonb
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS bot_log_profile_ts_idx ON bot_log (profile, ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS bot_log_ts_idx ON bot_log (ts)");
        jdbc.execute("ALTER TABLE mob_kill ADD COLUMN IF NOT EXISTS job_uid text");
    }
}
