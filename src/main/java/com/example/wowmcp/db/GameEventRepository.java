package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Статистика игры: события (добыча, смерти, продажи, снимки опыта и денег) и запуски задач бота.
 * Пишет сюда wow-mcp, читает модуль dashboard/.
 */
@Component
public class GameEventRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(GameEventRepository.class);

    public GameEventRepository(Database db) {
        super(db);
    }

    /** dataJson — JSON-объект со значениями события (может быть null). */
    public void insertEvent(String type, String source, String jobUid, Integer zone, Double x, Double y,
                            long atMillis, String dataJson) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO game_event (ts, type, source, job_uid, zone, x, y, data)
                    VALUES (?,?,?,?,?,?,?, CAST(? AS jsonb))""",
                    new Timestamp(atMillis), type, source, jobUid, zone, x, y, dataJson);
        } catch (Exception e) {
            log.warn("Не удалось записать событие {}: {}", type, e.getMessage());
        }
    }

    /** Запуск задачи бота (go_to, grind, hunt, ...). */
    public void jobStart(String uid, String name, long atMillis) {
        if (!isEnabled() || uid == null) return;
        try {
            jdbc().update("""
                    INSERT INTO bot_job (uid, name, started_at, status) VALUES (?,?,?, 'running')
                    ON CONFLICT (uid) DO NOTHING""", uid, name, new Timestamp(atMillis));
        } catch (Exception e) {
            log.warn("Не удалось записать запуск задачи: {}", e.getMessage());
        }
    }

    /** Конец задачи; если запись о запуске потерялась — создаётся целиком. */
    public void jobEnd(String uid, String name, long atMillis, String status, String result, String dataJson) {
        if (!isEnabled() || uid == null) return;
        try {
            jdbc().update("""
                    INSERT INTO bot_job (uid, name, started_at, ended_at, status, result, data)
                    VALUES (?,?,?,?,?,?, CAST(? AS jsonb))
                    ON CONFLICT (uid) DO UPDATE SET ended_at = EXCLUDED.ended_at, status = EXCLUDED.status,
                      result = EXCLUDED.result, data = EXCLUDED.data""",
                    uid, name, new Timestamp(atMillis), new Timestamp(atMillis), status, result, dataJson);
        } catch (Exception e) {
            log.warn("Не удалось записать конец задачи: {}", e.getMessage());
        }
    }
}
