package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Именованные места (place): «гарпии», «перекресток». Имена в нижнем регистре, без «ё». */
@Component
public class PlaceRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(PlaceRepository.class);

    public PlaceRepository(Database db) {
        super(db);
    }

    /** Запомнить место (перезаписывает с тем же именем). */
    public void remember(String name, int zone, double x, double y, String note) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO place (name, zone, x, y, note) VALUES (?,?,?,?,?)
                    ON CONFLICT (name) DO UPDATE SET zone = EXCLUDED.zone, x = EXCLUDED.x, y = EXCLUDED.y,
                      note = EXCLUDED.note, created_at = now()""",
                    name, zone, x, y, note);
        } catch (Exception e) {
            log.warn("Не удалось сохранить место: {}", e.getMessage());
        }
    }

    public Map<String, Object> find(String name) {
        if (!isEnabled()) return null;
        try {
            List<Map<String, Object>> rows = jdbc().queryForList(
                    "SELECT name, zone, x, y, note FROM place WHERE name = ?", name);
            return rows.isEmpty() ? null : plain(rows.get(0));
        } catch (Exception e) {
            log.warn("Не удалось прочитать место: {}", e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> listByZone(int zone) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().queryForList("SELECT name, zone, x, y, note FROM place WHERE zone = ? ORDER BY name", zone)
                    .stream().map(PlaceRepository::plain).toList();
        } catch (Exception e) {
            log.warn("Не удалось прочитать места: {}", e.getMessage());
            return List.of();
        }
    }

    /** x, y — double (в БД real → Float), чтобы значения безопасно уходили в JSON и в страницу. */
    private static Map<String, Object> plain(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("x", ((Number) row.get("x")).doubleValue());
        m.put("y", ((Number) row.get("y")).doubleValue());
        m.put("zone", ((Number) row.get("zone")).intValue());
        return m;
    }
}
