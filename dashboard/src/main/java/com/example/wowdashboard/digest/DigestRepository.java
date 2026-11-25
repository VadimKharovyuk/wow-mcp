package com.example.wowdashboard.digest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Только запросы к таблице bot_log за последние N минут (БД открыта на чтение). Никакой логики расчётов: показатели, флаги и итог
 * считают {@link Rates}, {@link Flags}, {@link Verdict}. Числа в сообщениях заменяются на N, чтобы одинаковые события с разными
 * номерами (хеши умений, секунды) складывались в одну строку.
 */
@Repository
class DigestRepository {

    private static final String SINCE = " ts >= now() - make_interval(mins => ?)";
    /** Сообщение без чисел и не длиннее 120 знаков — ключ группировки. */
    private static final String NORM = "regexp_replace(left(message, 120), '[0-9]+', 'N', 'g')";

    private final JdbcTemplate jdbc;

    DigestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String now() {
        return jdbc.queryForObject("SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS')", String.class);
    }

    List<String> profiles(int minutes) {
        return jdbc.queryForList("SELECT DISTINCT profile FROM bot_log WHERE profile IS NOT NULL AND" + SINCE, String.class, minutes);
    }

    List<Map<String, Object>> counts(int minutes) {
        return jdbc.queryForList("SELECT category, level, count(*) AS n FROM bot_log WHERE" + SINCE
                + " GROUP BY 1, 2 ORDER BY n DESC LIMIT 40", minutes);
    }

    /** Известный шум: умения без клавиши в ротации (игрок знает и не меняет) — считаем отдельно и не показываем как проблему. */
    long spellNotOnBars(int minutes) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM bot_log WHERE message LIKE 'spell not on bars%' AND" + SINCE, Long.class, minutes);
        return n == null ? 0 : n;
    }

    List<Map<String, Object>> problems(int minutes) {
        return jdbc.queryForList("SELECT category, level, " + NORM + " AS msg, count(*) AS n, "
                + "to_char(min(ts), 'HH24:MI:SS') AS first, to_char(max(ts), 'HH24:MI:SS') AS last, "
                + "left((array_agg(message ORDER BY ts DESC))[1], 160) AS sample "
                + "FROM bot_log WHERE level IN ('warn', 'error') AND message NOT LIKE 'spell not on bars%' AND" + SINCE
                + " GROUP BY 1, 2, 3 ORDER BY n DESC LIMIT 15", minutes);
    }

    List<Map<String, Object>> normal(int minutes) {
        return jdbc.queryForList("SELECT category, " + NORM + " AS msg, count(*) AS n FROM bot_log "
                + "WHERE level NOT IN ('warn', 'error') AND" + SINCE + " GROUP BY 1, 2 ORDER BY n DESC LIMIT 12", minutes);
    }

    /** Сообщения, которых не было в предыдущем периоде такой же длины. */
    List<Map<String, Object>> newInPeriod(int minutes) {
        return jdbc.queryForList("""
                WITH cur AS (SELECT category, level, %1$s AS msg, count(*) AS n FROM bot_log WHERE %2$s GROUP BY 1, 2, 3),
                     prev AS (SELECT DISTINCT category, level, %1$s AS msg FROM bot_log
                              WHERE ts >= now() - make_interval(mins => ?) AND ts < now() - make_interval(mins => ?))
                SELECT c.category, c.level, c.msg, c.n FROM cur c LEFT JOIN prev p USING (category, level, msg)
                WHERE p.msg IS NULL ORDER BY c.n DESC LIMIT 15""".formatted(NORM, SINCE), minutes, minutes * 2, minutes);
    }

    Map<String, Object> fights(int minutes) {
        return jdbc.queryForMap("""
                SELECT count(*) AS n,
                       round(avg((data->>'durMs')::numeric) / 1000, 1) AS avg_sec,
                       round((percentile_cont(0.9) WITHIN GROUP (ORDER BY (data->>'durMs')::numeric))::numeric / 1000, 1) AS p90_sec,
                       round(avg((data->>'hp0')::numeric - (data->>'hp1')::numeric), 1) AS avg_hp_loss_pct,
                       round(avg((data->>'attackers')::numeric), 2) AS avg_attackers,
                       count(*) FILTER (WHERE message <> 'бой окончен') AS not_ok
                FROM bot_log WHERE category = 'fight' AND data->>'durMs' IS NOT NULL AND""" + SINCE, minutes);
    }

    List<Map<String, Object>> fightResults(int minutes) {
        return jdbc.queryForList("SELECT message AS result, count(*) AS n FROM bot_log "
                + "WHERE category = 'fight' AND data->>'durMs' IS NOT NULL AND" + SINCE + " GROUP BY 1 ORDER BY n DESC LIMIT 8", minutes);
    }

    List<Map<String, Object>> switching(int minutes) {
        return jdbc.queryForList("SELECT " + NORM + " AS msg, coalesce(data->>'found', '') AS found, count(*) AS n FROM bot_log "
                + "WHERE category = 'fight' AND (message LIKE 'быстрое переключение%' OR message LIKE 'поиск атакующего%' "
                + "OR message LIKE 'доворот%' OR message LIKE 'переключился%') AND" + SINCE + " GROUP BY 1, 2 ORDER BY n DESC LIMIT 12", minutes);
    }

    List<Map<String, Object>> approachFails(int minutes) {
        return jdbc.queryForList("SELECT coalesce(data->>'why', '?') AS why, count(*) AS n FROM bot_log "
                + "WHERE category = 'approach' AND level = 'warn' AND" + SINCE + " GROUP BY 1 ORDER BY n DESC LIMIT 10", minutes);
    }

    List<Map<String, Object>> travel(int minutes) {
        return jdbc.queryForList("SELECT coalesce(data->>'mountOk', '?') AS mounted, coalesce(data->>'result', '?') AS result, "
                + "count(*) AS n, round(avg((data->>'rideMs')::numeric) / 1000, 1) AS avg_ride_sec FROM bot_log "
                + "WHERE category = 'travel' AND" + SINCE + " GROUP BY 1, 2 ORDER BY n DESC LIMIT 10", minutes);
    }

    List<Map<String, Object>> deaths(int minutes) {
        return jdbc.queryForList("SELECT to_char(ts, 'HH24:MI:SS') AS at, zone, left(message, 140) AS msg FROM bot_log "
                + "WHERE category = 'death' AND level = 'error' AND" + SINCE + " ORDER BY ts DESC LIMIT 6", minutes);
    }

    List<Map<String, Object>> luaAndBridge(int minutes) {
        return jdbc.queryForList("SELECT category, level, " + NORM + " AS msg, count(*) AS n FROM bot_log "
                + "WHERE category IN ('lua', 'bridge') AND level IN ('warn', 'error') AND" + SINCE + " GROUP BY 1, 2, 3 ORDER BY n DESC LIMIT 8", minutes);
    }

    /** Счётчики по трём равным частям периода (колонка part 0..2, от старой к новой); части без событий в результат не попадают. */
    List<Map<String, Object>> partRows(int minutes) {
        return jdbc.queryForList("""
                WITH b AS (
                  SELECT LEAST(2, floor(extract(epoch FROM (ts - (now() - make_interval(mins => ?)))) / (CAST(? AS numeric) * 20.0)))::int AS part,
                         category, level, message, data
                  FROM bot_log WHERE ts >= now() - make_interval(mins => ?))
                SELECT part,
                  count(*) FILTER (WHERE category = 'fight' AND data->>'durMs' IS NOT NULL) AS fights,
                  count(*) FILTER (WHERE category = 'fight' AND data->>'durMs' IS NOT NULL AND message <> 'бой окончен') AS fights_bad,
                  round(((avg((data->>'durMs')::numeric) FILTER (WHERE category = 'fight' AND data->>'durMs' IS NOT NULL)) / 1000)::numeric, 1) AS avg_fight_sec,
                  count(*) FILTER (WHERE message LIKE 'поиск атакующего%: найден') AS attacker_found,
                  count(*) FILTER (WHERE message LIKE 'поиск атакующего%: не найден') AS attacker_missed,
                  count(*) FILTER (WHERE message LIKE 'доворот%: нашёл') AS turn_found,
                  count(*) FILTER (WHERE message LIKE 'доворот%: не нашёл') AS turn_missed,
                  count(*) FILTER (WHERE category = 'travel' AND data->>'mountOk' = 'true') AS mount_ok,
                  count(*) FILTER (WHERE category = 'travel' AND data->>'mountOk' = 'false') AS mount_fail,
                  count(*) FILTER (WHERE category = 'approach' AND level = 'warn') AS approach_fail,
                  count(*) FILTER (WHERE category = 'approach' AND message LIKE '%дошёл%') AS approach_ok,
                  count(*) FILTER (WHERE category = 'death' AND level = 'error') AS deaths,
                  count(*) FILTER (WHERE level IN ('warn', 'error') AND message NOT LIKE 'spell not on bars%') AS warn_error,
                  count(*) FILTER (WHERE category = 'press_fail' AND message LIKE '%не лицом к цели%') AS press_not_facing,
                  count(*) FILTER (WHERE category = 'press_fail' AND message LIKE '%слишком далеко%') AS press_too_far,
                  count(*) FILTER (WHERE category = 'press_fail' AND message LIKE '%нет реакции%') AS press_no_reaction,
                  count(*) FILTER (WHERE category = 'press_fail' AND message LIKE '%другая ошибка игры%') AS press_other
                FROM b GROUP BY part ORDER BY part""", minutes, minutes, minutes);
    }
}
