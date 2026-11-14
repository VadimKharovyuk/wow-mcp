package com.example.wowmcp.db;

import com.example.wowmcp.config.WowProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    /** Сколько ошибок вставки подряд считаем признаком потери связи с БД. */
    private static final int MAX_FAILURES_IN_A_ROW = 5;

    private final WowProperties properties;
    private volatile boolean enabled = false;
    /** Ошибки вставки подряд: одна битая строка не должна выключать БД целиком. */
    private int failuresInARow = 0;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate namedJdbc;

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
            namedJdbc = new NamedParameterJdbcTemplate(dataSource);
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
            log.warn("Не удалось записать убийство: {}", e.getMessage(), e);
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
            log.warn("Не удалось записать точку пути: {}", e.getMessage(), e);
            disable();
        }
    }

    /**
     * Тёплые места охоты в зоне: убийства за последние 30 дней сгруппированы по сетке 1×1,
     * берутся только клетки с >= 2 убийствами, для мобов уровня в [minLvl, maxLvl].
     */
    public List<Map<String, Object>> querySpots(int zone, int minLvl, int maxLvl) {
        if (!enabled) return List.of();
        log.info("querySpots: zone={}, minLvl={}, maxLvl={} (типы: {} / {} / {})",
                zone, minLvl, maxLvl, int.class.getSimpleName(), int.class.getSimpleName(), int.class.getSimpleName());
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("zone", zone);
            params.put("minLvl", minLvl);
            params.put("maxLvl", maxLvl);
            return namedJdbc.query("""
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
                    params,
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
            log.warn("Не удалось прочитать spots: {}", e.getMessage(), e);
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
        String prof = normalizeProfile(profile);
        try {
            jdbc.update(
                    "INSERT INTO key_press (profile,key,hp,mana,ready,fight,target,target_hp_pct,since_fight_ms,prev_key,prev_key_ms,pressed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    prof, key, hp, mana, ready, fight, target, targetHpPct, sinceFightMs, prevKey, prevKeyMs,
                    new Timestamp(atMillis));
            failuresInARow = 0;
        } catch (Exception e) {
            warnInsertFailed("нажатие клавиши", e);
        }
    }

    /**
     * Записывает применение заклинания игроком. Возвращает true, если строка действительно вставлена
     * (по этому счётчику EventCollector пишет лог «вставлено cast=K»).
     */
    public boolean insertSpellCast(String profile, int nameHash, Integer spellId, Double hp, Double mana, Boolean fight,
                                   Boolean target, Double targetHpPct, List<Integer> readySpells, Integer sinceFightMs,
                                   Integer prevSpell, Integer prevSpellMs, long atMillis) {
        if (!enabled) return false;
        String prof = normalizeProfile(profile);
        try {
            // ready_spells передаём текстовым литералом массива и кастуем в int4[]:
            // так не нужно тащить java.sql.Array из чужого соединения пула.
            jdbc.update(
                    "INSERT INTO spell_cast (profile,name_hash,spell_id,hp,mana,fight,target,target_hp_pct,ready_spells,since_fight_ms,prev_spell,prev_spell_ms,cast_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,CAST(? AS int4[]),?,?,?,?)",
                    prof, nameHash, spellId, hp, mana, fight, target, targetHpPct, intArrayLiteral(readySpells),
                    sinceFightMs, prevSpell, prevSpellMs, new Timestamp(atMillis));
            failuresInARow = 0;
            return true;
        } catch (Exception e) {
            warnInsertFailed("применение заклинания", e);
            return false;
        }
    }

    /** {@code [1,2,3] -> "{1,2,3}"}; пустой/отсутствующий список -> null (колонка nullable). */
    static String intArrayLiteral(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.append('}').toString();
    }

    private static String normalizeProfile(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile;
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

    /** Запоминает активный профиль в БД, чтобы после перезапуска сервера его нашёл autoLoadProfile. */
    public void setActiveProfile(String profile) {
        if (!enabled) return;
        try {
            if (profile == null || profile.isBlank()) {
                jdbc.update("DELETE FROM active_profile WHERE id = 1");
                return;
            }
            jdbc.update("""
                    INSERT INTO active_profile (id, profile, updated_at) VALUES (1, ?, now())
                    ON CONFLICT (id) DO UPDATE SET profile = EXCLUDED.profile, updated_at = now()""",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось сохранить активный профиль: {}", e.getMessage(), e);
        }
    }

    /** Активный профиль из БД (null, если не задан). */
    public String findActiveProfile() {
        if (!enabled) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT profile FROM active_profile WHERE id = 1");
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("profile"));
        } catch (Exception e) {
            log.warn("Не удалось прочитать активный профиль: {}", e.getMessage(), e);
            return null;
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
            jdbc.update("DELETE FROM active_profile WHERE profile = ?", profile);
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

    // ---- vendor ----

    public void rememberVendor(int zone, double x, double y, String name, Double facing) {
        if (!enabled || name == null || name.isBlank()) return;
        try {
            jdbc.update("DELETE FROM vendor WHERE zone = ? AND name = ?", zone, name);
            jdbc.update("INSERT INTO vendor (zone,x,y,name,facing) VALUES (?,?,?,?,?)", zone, x, y, name, facing);
        } catch (Exception e) {
            log.warn("Не удалось сохранить торговца: {}", e.getMessage());
            disable();
        }
    }

    public List<Map<String, Object>> listVendors(int zone) {
        if (!enabled) return List.of();
        try {
            return jdbc.query("""
                    SELECT id, zone, x, y, name, facing, created_at
                    FROM vendor WHERE zone = ? ORDER BY created_at DESC""",
                    (rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getInt("id"));
                        m.put("zone", rs.getInt("zone"));
                        m.put("x", (double) rs.getFloat("x"));
                        m.put("y", (double) rs.getFloat("y"));
                        float facing = rs.getFloat("facing");
                        m.put("facing", rs.wasNull() ? null : (double) facing);
                        m.put("name", rs.getString("name"));
                        m.put("created_at", rs.getTimestamp("created_at"));
                        return m;
                    }, zone);
        } catch (Exception e) {
            log.warn("Не удалось прочитать торговцев: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> nearestVendor(int zone, double x, double y) {
        List<Map<String, Object>> list = listVendors(zone);
        Map<String, Object> best = null;
        double bestD = Double.MAX_VALUE;
        for (Map<String, Object> v : list) {
            double vx = ((Number) v.get("x")).doubleValue();
            double vy = ((Number) v.get("y")).doubleValue();
            double d = Math.hypot(vx - x, vy - y);
            if (d < bestD) { bestD = d; best = v; }
        }
        return best;
    }

    // ---- keep_item ----

    public void addKeepItem(int itemId) {
        if (!enabled) return;
        try {
            jdbc.update("INSERT INTO keep_item (item_id) VALUES (?) ON CONFLICT (item_id) DO NOTHING", itemId);
        } catch (Exception e) {
            // fallback for DBs without ON CONFLICT in some modes; try plain insert
            try {
                jdbc.update("INSERT INTO keep_item (item_id) VALUES (?)", itemId);
            } catch (Exception e2) {
                log.warn("Не удалось сохранить keep_item: {}", e2.getMessage());
                disable();
            }
        }
    }

    public List<Integer> listKeepItemIds() {
        if (!enabled) return List.of();
        try {
            return jdbc.query("SELECT item_id FROM keep_item ORDER BY item_id",
                    (rs, rowNum) -> rs.getInt("item_id"));
        } catch (Exception e) {
            log.warn("Не удалось прочитать keep_item: {}", e.getMessage());
            return List.of();
        }
    }

    private void disable() {
        enabled = false;
        closeQuietly();
    }

    /** WARN со стектрейсом; БД выключаем только если ошибки идут подряд (похоже, связь потеряна). */
    private void warnInsertFailed(String what, Exception e) {
        failuresInARow++;
        log.warn("Не удалось записать {} (ошибок подряд: {}): {}", what, failuresInARow, e.getMessage(), e);
        if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
            log.warn("БД отключается: {} ошибок вставки подряд", failuresInARow);
            disable();
        }
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
