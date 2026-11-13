package com.example.wowmcp.db;

import com.example.wowmcp.config.WowProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DbService {

    private static final Logger log = LoggerFactory.getLogger(DbService.class);

    private final WowProperties properties;
    private volatile boolean enabled = false;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    public DbService(WowProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String url = properties.dbUrl();
        if (url == null || url.isBlank()) {
            log.info("wow.db.url не задан — БД отключена, события не сохраняются");
            return;
        }
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(properties.dbUsername());
            cfg.setPassword(properties.dbPassword());
            cfg.setMaximumPoolSize(2);
            cfg.setConnectionTimeout(5000);
            dataSource = new HikariDataSource(cfg);
            dataSource.getConnection().close();
            jdbc = new JdbcTemplate(dataSource);
            createSchema();
            enabled = true;
            log.info("Подключено к БД: {}", url);
        } catch (Exception e) {
            log.warn("БД недоступна ({}): {} — сервер продолжает работу без сохранения", url, e.getMessage());
            closeQuietly();
            enabled = false;
        }
    }

    private void createSchema() {
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
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void insertMobKill(int zone, double x, double y, Integer mobLevel, Integer playerLevel,
                              String source, long atMillis) {
        if (!enabled) return;
        try {
            jdbc.update(
                    "INSERT INTO mob_kill (zone,x,y,mob_level,player_level,source,killed_at) VALUES (?,?,?,?,?,?,?)",
                    zone, x, y, mobLevel, playerLevel, source, new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать убийство: {}", e.getMessage());
            disable();
        }
    }

    public void insertPathPoint(String sessionId, int zone, double x, double y, long atMillis) {
        if (!enabled) return;
        try {
            jdbc.update(
                    "INSERT INTO path_point (session_id,zone,x,y,recorded_at) VALUES (?,?,?,?,?)",
                    sessionId, zone, x, y, new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать точку пути: {}", e.getMessage());
            disable();
        }
    }

    /**
     * Тёплые места охоты в зоне: убийства за последние 30 дней сгруппированы по сетке 1×1,
     * берутся только клетки с >= 2 убийствами, для мобов уровня в [minLvl, maxLvl].
     */
    public List<Map<String, Object>> querySpots(int zone, int minLvl, int maxLvl) {
        if (!enabled) return List.of();
        try {
            return jdbc.query("""
                    SELECT round(avg(x)::numeric, 2)  AS x,
                           round(avg(y)::numeric, 2)  AS y,
                           count(*)                   AS kills,
                           round(avg(mob_level))      AS lvl,
                           max(killed_at)             AS last_kill
                    FROM mob_kill
                    WHERE zone = :zone
                      AND killed_at > now() - interval '30 days'
                      AND (mob_level IS NULL OR mob_level BETWEEN :minLvl AND :maxLvl)
                    GROUP BY floor(x / 1.0), floor(y / 1.0)
                    HAVING count(*) >= 2
                    ORDER BY kills DESC
                    LIMIT 40""",
                    new Object[]{zone, minLvl, maxLvl},
                    (rs, rowNum) -> {
                        Map<String, Object> spot = new LinkedHashMap<>();
                        spot.put("x", rs.getDouble("x"));
                        spot.put("y", rs.getDouble("y"));
                        spot.put("kills", rs.getLong("kills"));
                        spot.put("lvl", rs.getDouble("lvl"));
                        spot.put("last_kill", rs.getTimestamp("last_kill"));
                        return spot;
                    });
        } catch (Exception e) {
            log.warn("Не удалось прочитать spots: {}", e.getMessage());
            return List.of();
        }
    }

    public long countPathPoints(String sessionId) {
        if (!enabled || sessionId == null) return 0;
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM path_point WHERE session_id = ?", Long.class, sessionId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("Не удалось посчитать точки пути: {}", e.getMessage());
            return 0;
        }
    }

    public long countKillsSince(Instant since) {
        if (!enabled || since == null) return 0;
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM mob_kill WHERE killed_at >= ?", Long.class,
                    Timestamp.from(since));
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("Не удалось посчитать убийства: {}", e.getMessage());
            return 0;
        }
    }

    public void insertKeyPress(String profile, String key, Double hp, Double mana, Integer ready, Boolean fight,
                               Boolean target, Double targetHpPct, Integer sinceFightMs, String prevKey,
                               Integer prevKeyMs, long atMillis) {
        if (!enabled) return;
        try {
            jdbc.update(
                    "INSERT INTO key_press (profile,key,hp,mana,ready,fight,target,target_hp_pct,since_fight_ms,prev_key,prev_key_ms,pressed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    profile, key, hp, mana, ready, fight, target, targetHpPct, sinceFightMs, prevKey, prevKeyMs,
                    new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать нажатие: {}", e.getMessage());
            disable();
        }
    }

    public void insertSpellCast(String profile, int nameHash, Integer spellId, Double hp, Double mana, Boolean fight,
                                Boolean target, Double targetHpPct, List<Integer> readySpells, Integer sinceFightMs,
                                Integer prevSpell, Integer prevSpellMs, long atMillis) {
        if (!enabled) return;
        try {
            java.sql.Array readyArray = null;
            if (readySpells != null && !readySpells.isEmpty()) {
                java.sql.Connection conn = jdbc.getDataSource().getConnection();
                try {
                    readyArray = conn.createArrayOf("int4", readySpells.toArray(new Integer[0]));
                } finally {
                    conn.close();
                }
            }
            jdbc.update(
                    "INSERT INTO spell_cast (profile,name_hash,spell_id,hp,mana,fight,target,target_hp_pct,ready_spells,since_fight_ms,prev_spell,prev_spell_ms,cast_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    profile, nameHash, spellId, hp, mana, fight, target, targetHpPct, readyArray, sinceFightMs, prevSpell,
                    prevSpellMs, new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать применение заклинания: {}", e.getMessage());
            disable();
        }
    }

    /** Применения заклинаний профиля за последние 14 дней. */
    public List<Map<String, Object>> querySpellCasts(String profile) {
        if (!enabled || profile == null) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT name_hash, spell_id, hp, mana, fight, target, target_hp_pct, ready_spells, "
                            + "since_fight_ms, prev_spell, prev_spell_ms, cast_at "
                            + "FROM spell_cast WHERE profile = ? AND cast_at >= (now() - interval '14 days') ORDER BY cast_at",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось прочитать применения заклинаний: {}", e.getMessage());
            return List.of();
        }
    }

    /** Нажатия профиля за последние 14 дней. */
    public List<Map<String, Object>> queryKeyPresses(String profile) {
        if (!enabled || profile == null) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT key, hp, mana, ready, fight, target, target_hp_pct, since_fight_ms, prev_key, prev_key_ms, pressed_at "
                            + "FROM key_press WHERE profile = ? AND pressed_at >= (now() - interval '14 days') ORDER BY pressed_at",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось прочитать нажатия: {}", e.getMessage());
            return List.of();
        }
    }

    public void upsertRotationProfile(String profile, String rotationJson, int samples, String statsJson) {
        if (!enabled) return;
        try {
            jdbc.update("""
                    INSERT INTO rotation_profile (profile, rotation_json, samples, learned_at, last_used_at, stats_json)
                    VALUES (?,?,?,?,now(),?)
                    ON CONFLICT (profile) DO UPDATE SET
                      rotation_json = EXCLUDED.rotation_json,
                      samples = EXCLUDED.samples,
                      learned_at = EXCLUDED.learned_at,
                      last_used_at = now(),
                      stats_json = EXCLUDED.stats_json""",
                    profile, rotationJson, samples, new Timestamp(System.currentTimeMillis()), statsJson);
        } catch (Exception e) {
            log.warn("Не удалось сохранить профиль ротации: {}", e.getMessage());
            disable();
        }
    }

    public Map<String, Object> loadRotationProfile(String profile) {
        if (!enabled || profile == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT rotation_json, samples, stats_json, last_used_at FROM rotation_profile WHERE profile = ?", profile);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("Не удалось загрузить профиль ротации: {}", e.getMessage());
            return null;
        }
    }

    public void touchProfile(String profile) {
        if (!enabled || profile == null) return;
        try {
            jdbc.update("UPDATE rotation_profile SET last_used_at = now() WHERE profile = ?", profile);
        } catch (Exception e) {
            log.warn("Не удалось обновить last_used_at: {}", e.getMessage());
        }
    }

    public void deleteProfileData(String profile) {
        if (!enabled || profile == null) return;
        try {
            jdbc.update("DELETE FROM key_press WHERE profile = ?", profile);
            jdbc.update("DELETE FROM rotation_profile WHERE profile = ?", profile);
        } catch (Exception e) {
            log.warn("Не удалось удалить данные профиля: {}", e.getMessage());
            disable();
        }
    }

    public String findLastUsedProfile() {
        if (!enabled) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT profile FROM rotation_profile ORDER BY last_used_at DESC LIMIT 1");
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("profile"));
        } catch (Exception e) {
            log.warn("Не удалось найти последний профиль: {}", e.getMessage());
            return null;
        }
    }

    private void disable() {
        enabled = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (dataSource != null) dataSource.close();
        } catch (Exception ignored) {
        }
        dataSource = null;
        jdbc = null;
    }

    @PreDestroy
    public void destroy() {
        closeQuietly();
    }
}
