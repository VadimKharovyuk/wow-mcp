package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные охоты: зона бота → zone_id (zone_map), имена мобов (mob_spawn), кластеры (mob_cluster)
 * и статистика посещений (hunt_cluster_stat). mob_spawn, zone_map и mob_cluster импортирует игрок.
 */
@Component
public class HuntRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(HuntRepository.class);

    public HuntRepository(Database db) {
        super(db);
    }

    /** zone_id по зоне бота (поле zone из get_state); null — зоны нет в zone_map. */
    public Integer zoneId(int botZone) {
        if (!isEnabled()) return null;
        try {
            List<Integer> ids = jdbc().queryForList("SELECT zone_id FROM zone_map WHERE bot_zone = ?", Integer.class, botZone);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("Не удалось прочитать zone_map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Имена атакуемых обычных мобов (fac = '', rnk = 0) вокруг точки под уровень игрока, до 6 штук,
     * по числу точек появления. Нужны, чтобы находить нейтральных (жёлтых) мобов через /target имя.
     */
    public List<String> namesAround(int zoneId, int pLvl, double x, double y, double radius) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().queryForList("""
                    SELECT name_ru FROM mob_spawn
                    WHERE zone_id = ? AND fac = '' AND rnk = 0 AND lvl_max BETWEEN ? AND ?
                      AND sqrt(power(x - CAST(? AS float8), 2) + power(y - CAST(? AS float8), 2)) <= CAST(? AS float8)
                    GROUP BY name_ru ORDER BY count(*) DESC, name_ru LIMIT 6""",
                    String.class, zoneId, pLvl - 4, pLvl + 2, x, y, radius);
        } catch (Exception e) {
            log.warn("Не удалось прочитать имена мобов: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Точки появления обычных мобов (fac = '', rnk = 0) под уровень вокруг точки — как места охоты для режима «радиус».
     * Группируются по сетке 0,4×0,4; вес (kills) = число спавнов в клетке, не больше 3. Формат как у KillRepository.querySpots.
     */
    public List<Map<String, Object>> spawnSpots(int zoneId, int minLvl, int maxLvl, double x, double y, double radius) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT round(avg(x)::numeric, 2) AS x, round(avg(y)::numeric, 2) AS y,
                           least(count(*), 3) AS kills, round(avg(lvl_max)) AS lvl
                    FROM mob_spawn
                    WHERE zone_id = ? AND fac = '' AND rnk = 0 AND lvl_max BETWEEN ? AND ?
                      AND sqrt(power(x - CAST(? AS float8), 2) + power(y - CAST(? AS float8), 2)) <= CAST(? AS float8)
                    GROUP BY floor(x / 0.4), floor(y / 0.4)
                    ORDER BY count(*) DESC LIMIT 100""",
                    (rs, i) -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("x", rs.getDouble("x"));
                        m.put("y", rs.getDouble("y"));
                        m.put("kills", rs.getLong("kills"));
                        m.put("lvl", rs.getDouble("lvl"));
                        return m;
                    }, zoneId, minLvl, maxLvl, x, y, radius);
        } catch (Exception e) {
            log.warn("Не удалось прочитать точки появления: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Кластеры под уровень игрока, не в «пустых» (empty_until), в пределах maxDistance, лучшие первыми.
     * Оценка: spawns * found_rate / (1 + расстояние/10); found_rate = found/visits (без посещений — 1).
     */
    public List<Map<String, Object>> bestClusters(int zoneId, int pLvl, double x, double y, double maxDistance, int limit) {
        if (!isEnabled()) return List.of();
        try {
            List<Map<String, Object>> rows = jdbc().query("""
                    SELECT m.cx, m.cy, m.lvl_min, m.lvl_max, m.spawns, m.mobs, s.visits, s.found
                    FROM mob_cluster m
                    LEFT JOIN hunt_cluster_stat s
                      ON s.zone_id = m.zone_id AND s.cx = round(CAST(m.cx AS numeric), 2) AND s.cy = round(CAST(m.cy AS numeric), 2)
                    WHERE m.zone_id = ? AND m.lvl_max BETWEEN ? AND ? AND m.lvl_min >= ?
                      AND (s.empty_until IS NULL OR s.empty_until <= now())""",
                    (rs, i) -> {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("cx", rs.getDouble("cx"));
                        c.put("cy", rs.getDouble("cy"));
                        c.put("lvlMin", rs.getInt("lvl_min"));
                        c.put("lvlMax", rs.getInt("lvl_max"));
                        c.put("spawns", rs.getInt("spawns"));
                        c.put("mobs", rs.getString("mobs"));
                        c.put("visits", rs.getInt("visits"));
                        c.put("found", rs.getInt("found"));
                        return c;
                    }, zoneId, pLvl - 4, pLvl + 1, pLvl - 5);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> c : rows) {
                double dist = Math.hypot((Double) c.get("cx") - x, (Double) c.get("cy") - y);
                if (dist > maxDistance) continue;
                int visits = (Integer) c.get("visits");
                double foundRate = visits > 0 ? (double) (Integer) c.get("found") / visits : 1.0;
                c.put("distance", Math.round(dist * 10) / 10.0);
                c.put("score", score((Integer) c.get("spawns"), foundRate, dist));
                out.add(c);
            }
            out.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } catch (Exception e) {
            log.warn("Не удалось прочитать кластеры: {}", e.getMessage());
            return List.of();
        }
    }

    static double score(int spawns, double foundRate, double distance) {
        return spawns * foundRate / (1 + distance / 10.0);
    }

    /**
     * Записать результат посещения кластера. emptyMinutes > 0 — пометить «пустым/недоступным» на это время.
     */
    public void recordVisit(int zoneId, double cx, double cy, boolean found, int kills, int emptyMinutes, String note) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO hunt_cluster_stat (zone_id, cx, cy, visits, found, kills, last_visit, empty_until, note)
                    VALUES (?, round(CAST(? AS numeric), 2), round(CAST(? AS numeric), 2), 1, ?, ?, now(),
                            CASE WHEN CAST(? AS int) > 0 THEN now() + (CAST(? AS int) * interval '1 minute') END, ?)
                    ON CONFLICT (zone_id, cx, cy) DO UPDATE SET
                      visits = hunt_cluster_stat.visits + 1,
                      found = hunt_cluster_stat.found + EXCLUDED.found,
                      kills = hunt_cluster_stat.kills + EXCLUDED.kills,
                      last_visit = now(),
                      empty_until = COALESCE(EXCLUDED.empty_until, hunt_cluster_stat.empty_until),
                      note = COALESCE(EXCLUDED.note, hunt_cluster_stat.note)""",
                    zoneId, cx, cy, found ? 1 : 0, kills, emptyMinutes, emptyMinutes, note);
        } catch (Exception e) {
            log.warn("Не удалось записать статистику кластера: {}", e.getMessage());
        }
    }
}
