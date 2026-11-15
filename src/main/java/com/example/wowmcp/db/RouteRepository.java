package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Маршруты: имя → записанный путь (route.session_id → path_point). */
@Component
public class RouteRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(RouteRepository.class);

    /** Точка пути. */
    public record Point(int zone, double x, double y) {}

    public RouteRepository(Database db) {
        super(db);
    }

    /** Сохранить запись под именем (перезаписывает). Возвращает число точек в записи. */
    public long save(String name, String sessionId) {
        if (!isEnabled() || sessionId == null) return 0;
        try {
            Long n = jdbc().queryForObject("SELECT count(*) FROM path_point WHERE session_id = ?", Long.class, sessionId);
            if (n == null || n == 0) return 0;
            jdbc().update("""
                    INSERT INTO route (name, session_id) VALUES (?, ?)
                    ON CONFLICT (name) DO UPDATE SET session_id = EXCLUDED.session_id""", name, sessionId);
            return n;
        } catch (Exception e) {
            log.warn("Не удалось сохранить маршрут: {}", e.getMessage());
            return 0;
        }
    }

    /** Имена маршрутов с их session_id. */
    public List<String[]> listNames() {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("SELECT name, session_id FROM route ORDER BY name",
                    (rs, i) -> new String[]{rs.getString("name"), rs.getString("session_id")});
        } catch (Exception e) {
            log.warn("Не удалось прочитать маршруты: {}", e.getMessage());
            return List.of();
        }
    }

    public String sessionOf(String name) {
        if (!isEnabled()) return null;
        try {
            List<String> ids = jdbc().queryForList("SELECT session_id FROM route WHERE name = ?", String.class, name);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("Не удалось прочитать маршрут: {}", e.getMessage());
            return null;
        }
    }

    /** Точки записи по порядку записи. */
    public List<Point> points(String sessionId) {
        if (!isEnabled() || sessionId == null) return List.of();
        try {
            return new ArrayList<>(jdbc().query(
                    "SELECT zone, x, y FROM path_point WHERE session_id = ? ORDER BY recorded_at, id",
                    (rs, i) -> new Point(rs.getInt("zone"), rs.getDouble("x"), rs.getDouble("y")), sessionId));
        } catch (Exception e) {
            log.warn("Не удалось прочитать точки пути: {}", e.getMessage());
            return List.of();
        }
    }
}
