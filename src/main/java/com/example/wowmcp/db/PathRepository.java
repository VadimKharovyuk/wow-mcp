package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/** Точки записанного пути (path_point). */
@Component
public class PathRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(PathRepository.class);

    public PathRepository(Database db) {
        super(db);
    }

    /** source: manual (record_start), player (игрок ведёт сам), bot (движение бота). */
    public void insertPathPoint(String sessionId, int zone, double x, double y, long atMillis, String source) {
        if (!isEnabled()) return;
        try {
            jdbc().update(
                    "INSERT INTO path_point (session_id,zone,x,y,recorded_at,source) VALUES (?,?,?,?,?,?)",
                    sessionId, zone, x, y, new Timestamp(atMillis), source == null ? "manual" : source);
        } catch (Exception e) {
            log.warn("Не удалось записать точку пути: {}", e.getMessage(), e);
            db.failed(e);
        }
    }

    public long countPathPoints(String sessionId) {
        if (!isEnabled() || sessionId == null) return 0;
        try {
            Long n = jdbc().queryForObject("SELECT count(*) FROM path_point WHERE session_id = ?", Long.class, sessionId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("Не удалось посчитать точки пути: {}", e.getMessage());
            return 0;
        }
    }
}
