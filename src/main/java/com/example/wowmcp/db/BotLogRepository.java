package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Журнал бота: ошибки и важные события боя по профилям (paladin / hunter ...), хранится 10 дней.
 * Пишет EventCollector (события типа {@code log} из JS-бота), читают Telegram-команда «ошибки» и MCP-инструмент logs.
 */
@Component
public class BotLogRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(BotLogRepository.class);

    /** Сколько дней хранить записи. */
    public static final int KEEP_DAYS = 10;

    public BotLogRepository(Database db) {
        super(db);
    }

    public void insert(long atMillis, String profile, String level, String category, String message,
                       Integer zone, Double x, Double y, String jobUid, String dataJson) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO bot_log (ts, profile, level, category, message, zone, x, y, job_uid, data)
                    VALUES (?,?,?,?,?,?,?,?,?, CAST(? AS jsonb))""",
                    new Timestamp(atMillis), profile, level, category, message, zone, x, y, jobUid, dataJson);
        } catch (Exception e) {
            log.warn("Не удалось записать лог бота: {}", e.getMessage());
        }
    }

    /**
     * Последние записи (новые первыми). profile / category — null = любые; levels — какие уровни брать
     * (например warn+error); sinceMillis — не старше.
     */
    public List<Map<String, Object>> recent(String profile, String category, List<String> levels, long sinceMillis, int limit) {
        if (!isEnabled()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT ts, profile, level, category, message, zone, x, y, data FROM bot_log WHERE ts >= ?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(new Timestamp(sinceMillis));
        if (profile != null && !profile.isBlank()) { sql.append(" AND profile = ?"); args.add(profile); }
        if (category != null && !category.isBlank()) { sql.append(" AND category = ?"); args.add(category); }
        if (levels != null && !levels.isEmpty()) {
            sql.append(" AND level IN (").append(String.join(",", java.util.Collections.nCopies(levels.size(), "?"))).append(")");
            args.addAll(levels);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        try {
            return jdbc().queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.warn("Не удалось прочитать лог бота: {}", e.getMessage());
            return List.of();
        }
    }

    /** Сводка за период: сколько записей по категориям и уровням (для ответа «ошибки» без деталей). */
    public List<Map<String, Object>> summary(String profile, long sinceMillis) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().queryForList("""
                    SELECT category, level, count(*) AS n FROM bot_log
                    WHERE ts >= ? AND (?::text IS NULL OR profile = ?)
                    GROUP BY category, level ORDER BY n DESC""",
                    new Timestamp(sinceMillis), profile, profile);
        } catch (Exception e) {
            log.warn("Не удалось собрать сводку лога: {}", e.getMessage());
            return List.of();
        }
    }

    /** Удалить записи старше {@link #KEEP_DAYS} дней; возвращает число удалённых. */
    public int purgeOld() {
        if (!isEnabled()) return 0;
        try {
            return jdbc().update("DELETE FROM bot_log WHERE ts < now() - make_interval(days => ?)", KEEP_DAYS);
        } catch (Exception e) {
            log.warn("Не удалось почистить лог бота: {}", e.getMessage());
            return 0;
        }
    }
}
