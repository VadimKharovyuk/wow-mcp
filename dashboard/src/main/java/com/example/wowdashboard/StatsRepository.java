package com.example.wowdashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Запросы статистики. БД открыта только на чтение; таблицы создаёт wow-mcp. */
@Repository
class StatsRepository {

    private final JdbcTemplate jdbc;

    StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ведущий пробел нужен: в текстовых блоках пробелы в конце строки отбрасываются ("AND" + "ts" склеились бы)
    private static final String SINCE_KILL = " killed_at >= now() - make_interval(hours => ?)";
    private static final String SINCE_EVENT = " ts >= now() - make_interval(hours => ?)";

    /** Сводка за период: убийства, добыча, смерти, продажи, опыт, деньги, время работы бота. */
    Map<String, Object> summary(int hours) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("kills", bySource("SELECT source, count(*) AS n FROM mob_kill WHERE " + SINCE_KILL + " GROUP BY source", hours));
        out.put("loot", bySource("SELECT coalesce(source,'?') AS source, count(*) AS n FROM game_event WHERE type='loot' AND "
                + SINCE_EVENT + " GROUP BY source", hours));
        out.put("lootMiss", bySource("SELECT coalesce(data->>'result', '?') AS source, count(*) AS n FROM game_event "
                + "WHERE type='lootmiss' AND " + SINCE_EVENT + " GROUP BY 1", hours));
        out.put("deaths", bySource("SELECT coalesce(source,'?') AS source, count(*) AS n FROM game_event WHERE type='death' AND "
                + SINCE_EVENT + " GROUP BY source", hours));
        Map<String, Object> sells = jdbc.queryForMap("""
                SELECT count(*) AS sells, coalesce(sum((data->>'sold')::int), 0) AS items,
                       coalesce(sum((data->>'moneyDelta')::bigint), 0) AS money
                FROM game_event WHERE type='sell' AND """ + SINCE_EVENT, hours);
        out.put("sells", sells);
        out.put("botSeconds", jdbc.queryForObject("""
                SELECT coalesce(sum(extract(epoch FROM (coalesce(ended_at, now()) - started_at))), 0)::bigint
                FROM bot_job WHERE name IN ('grind','hunt','goRoute','go','sellAt','revive')
                  AND coalesce(ended_at, now()) >= now() - make_interval(hours => ?)""", Long.class, hours));
        out.put("xpGained", xpGained(hours));
        out.put("moneyNet", moneyNet(hours));
        return out;
    }

    private Map<String, Long> bySource(String sql, int hours) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            m.put(String.valueOf(rs.getString(1)), rs.getLong(2));
        }, hours);
        return m;
    }

    /**
     * Опыт за период по снимкам stat: при том же уровне — прирост, при повышении уровня — остаток до уровня + новый опыт.
     */
    private long xpGained(int hours) {
        Long v = jdbc.queryForObject("""
                SELECT coalesce(sum(CASE
                         WHEN prev_level IS NULL THEN 0
                         WHEN level = prev_level THEN greatest(xp - prev_xp, 0)
                         WHEN level > prev_level THEN greatest(prev_xpmax - prev_xp, 0) + xp
                         ELSE 0 END), 0)::bigint
                FROM (SELECT (data->>'level')::int AS level, (data->>'xp')::bigint AS xp,
                             lag((data->>'level')::int) OVER w AS prev_level,
                             lag((data->>'xp')::bigint) OVER w AS prev_xp,
                             lag((data->>'xpMax')::bigint) OVER w AS prev_xpmax
                      FROM game_event WHERE type='stat' AND """ + SINCE_EVENT + """
                      WINDOW w AS (ORDER BY ts)) t""", Long.class, hours);
        return v == null ? 0 : v;
    }

    /** Изменение денег за период (в меди): последний снимок минус первый. Траты на ремонт и покупки вычитаются. */
    private long moneyNet(int hours) {
        List<Long> rows = jdbc.queryForList("""
                SELECT (data->>'money')::bigint FROM game_event WHERE type='stat' AND """ + SINCE_EVENT + """
                ORDER BY ts""", Long.class, hours);
        return rows.size() < 2 ? 0 : rows.get(rows.size() - 1) - rows.get(0);
    }

    /** Убийства и добыча по времени: час (период ≤ 48 ч) или сутки. */
    List<Map<String, Object>> timeline(int hours) {
        String bucket = Periods.bucket(hours);       // из белого списка: 'hour' | 'day'
        return jdbc.queryForList("""
                SELECT t, kind, source, count(*) AS n FROM (
                  SELECT date_trunc('%1$s', killed_at) AS t, 'kill' AS kind, source FROM mob_kill WHERE %2$s
                  UNION ALL
                  SELECT date_trunc('%1$s', ts), type, coalesce(source,'?') FROM game_event
                  WHERE type IN ('loot','death') AND %3$s
                ) x GROUP BY t, kind, source ORDER BY t""".formatted(bucket, SINCE_KILL, SINCE_EVENT), hours, hours);
    }

    /** Опыт и деньги по времени (снимки), не больше 1500 точек. */
    List<Map<String, Object>> progress(int hours) {
        return jdbc.queryForList("""
                SELECT ts, (data->>'level')::int AS level, (data->>'xp')::bigint AS xp, (data->>'xpMax')::bigint AS xp_max,
                       (data->>'money')::bigint AS money
                FROM game_event WHERE type='stat' AND """ + SINCE_EVENT + " ORDER BY ts DESC LIMIT 1500", hours);
    }

    /** Последние запуски задач с числом убийств и окон добычи в каждом. */
    List<Map<String, Object>> jobs(int limit) {
        return jdbc.queryForList("""
                SELECT j.uid, j.name, j.started_at, j.ended_at, j.status, j.result,
                       extract(epoch FROM (coalesce(j.ended_at, now()) - j.started_at))::int AS seconds,
                       (SELECT count(*) FROM mob_kill k WHERE k.job_uid = j.uid) AS kills,
                       (SELECT count(*) FROM game_event e WHERE e.job_uid = j.uid AND e.type='loot') AS loot,
                       (SELECT count(*) FROM game_event e WHERE e.job_uid = j.uid AND e.type='lootmiss') AS loot_miss,
                       (SELECT count(*) FROM game_event e WHERE e.job_uid = j.uid AND e.type='death') AS deaths
                FROM bot_job j ORDER BY j.started_at DESC LIMIT ?""", limit);
    }

    /** Клетки 2×2 единицы карты с числом убийств (бот / игрок) — карта охоты. */
    List<Map<String, Object>> spots(int hours, Integer zone) {
        String where = SINCE_KILL + (zone == null ? "" : " AND zone = ?");
        Object[] args = zone == null ? new Object[]{hours} : new Object[]{hours, zone};
        return jdbc.queryForList("""
                SELECT zone, floor(x / 2) * 2 + 1 AS cx, floor(y / 2) * 2 + 1 AS cy, count(*) AS kills,
                       count(*) FILTER (WHERE source = 'bot') AS bot, count(*) FILTER (WHERE source = 'player') AS player
                FROM mob_kill WHERE %s GROUP BY zone, 2, 3 ORDER BY kills DESC LIMIT 80""".formatted(where), args);
    }
}
