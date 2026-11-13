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

    public List<Map<String, Object>> querySpots(int zone) {
        if (!enabled) return List.of();
        try {
            return jdbc.queryForList("""
                    SELECT floor(x)::int AS gx, floor(y)::int AS gy,
                           count(*) AS kills,
                           avg(x) AS avg_x, avg(y) AS avg_y,
                           avg(mob_level) AS avg_mob_level,
                           max(killed_at) AS last_kill
                    FROM mob_kill
                    WHERE zone = ?
                    GROUP BY floor(x)::int, floor(y)::int
                    ORDER BY count(*) DESC
                    LIMIT 30""", zone);
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
