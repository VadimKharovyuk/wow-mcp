package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Убийства мобов (mob_kill) и тёплые места охоты. */
@Component
public class KillRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(KillRepository.class);

    public KillRepository(Database db) {
        super(db);
    }

    public void insertMobKill(int zone, double x, double y, Integer mobLevel, Integer playerLevel,
                              String source, long atMillis) {
        if (!isEnabled()) return;
        try {
            jdbc().update(
                    "INSERT INTO mob_kill (zone,x,y,mob_level,player_level,source,killed_at) VALUES (?,?,?,?,?,?,?)",
                    zone, x, y, mobLevel, playerLevel, source, new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать убийство: {}", e.getMessage(), e);
            db.disable();
        }
    }

    /**
     * Тёплые места охоты в зоне: убийства за последние 30 дней сгруппированы по сетке 1×1,
     * берутся только клетки с >= 2 убийствами, для мобов уровня в [minLvl, maxLvl].
     */
    public List<Map<String, Object>> querySpots(int zone, int minLvl, int maxLvl) {
        if (!isEnabled()) return List.of();
        log.info("querySpots: zone={}, minLvl={}, maxLvl={} (типы: {} / {} / {})",
                zone, minLvl, maxLvl, int.class.getSimpleName(), int.class.getSimpleName(), int.class.getSimpleName());
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("zone", zone);
            params.put("minLvl", minLvl);
            params.put("maxLvl", maxLvl);
            return named().query("""
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

    public long countKillsSince(Instant since) {
        if (!isEnabled() || since == null) return 0;
        try {
            Long n = jdbc().queryForObject("SELECT count(*) FROM mob_kill WHERE killed_at >= ?", Long.class,
                    Timestamp.from(since));
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("Не удалось посчитать убийства: {}", e.getMessage());
            return 0;
        }
    }
}
