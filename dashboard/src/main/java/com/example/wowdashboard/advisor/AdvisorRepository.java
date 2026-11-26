package com.example.wowdashboard.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Правила советника и постоянные точки упора (таблицы создаёт wow-mcp; только чтение). Пока таблиц нет (основной сервер ещё не
 * перезапускали после обновления), методы возвращают пустые списки, а не ошибку.
 */
@Repository
class AdvisorRepository {

    private static final Logger log = LoggerFactory.getLogger(AdvisorRepository.class);

    private final JdbcTemplate jdbc;

    AdvisorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Действующие правила и закончившиеся за последние сутки (новые первыми). */
    List<Map<String, Object>> directives(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT to_char(created_at, 'MM-DD HH24:MI') AS created, kind, zone, x, y, r,
                           to_char(until_at, 'MM-DD HH24:MI') AS until, reason, status, (until_at > now()) AS active
                    FROM advisor_directive WHERE until_at > now() - interval '24 hours'
                    ORDER BY (until_at > now()) DESC, created_at DESC LIMIT ?""", limit);
        } catch (Exception e) {
            log.debug("Правила советника недоступны: {}", e.getMessage());
            return List.of();
        }
    }

    /** Места, где бот упирался чаще всего за последние 30 дней. */
    List<Map<String, Object>> stuckPoints(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT zone, cx AS x, cy AS y, n, to_char(last_at, 'MM-DD HH24:MI') AS last
                    FROM stuck_point WHERE last_at >= now() - interval '30 days' ORDER BY n DESC, last_at DESC LIMIT ?""", limit);
        } catch (Exception e) {
            log.debug("Точки упора недоступны: {}", e.getMessage());
            return List.of();
        }
    }
}
