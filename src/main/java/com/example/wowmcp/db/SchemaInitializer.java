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
    }
}
