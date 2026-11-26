package com.example.wowmcp.advisor;

import com.example.wowmcp.db.BaseRepository;
import com.example.wowmcp.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/** Постоянные точки упора, сигналы для советника (смерти, зависшие бои) и таблица правил. Ошибки БД не пробрасываются. */
@Repository
public class AdvisorRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(AdvisorRepository.class);

    public AdvisorRepository(Database db) {
        super(db);
    }

    /** Упор бота: счётчик клетки сетки 0,1 растёт (повторы одного упора бот уже склеивает сам — за 20 с это один случай). */
    public void recordStuck(int zone, double x, double y, long atMillis) {
        if (!isEnabled()) return;
        try {
            Timestamp at = new Timestamp(atMillis);
            jdbc().update("""
                    INSERT INTO stuck_point (zone, cx, cy, n, first_at, last_at)
                    VALUES (?, round(CAST(? AS numeric), 1), round(CAST(? AS numeric), 1), 1, ?, ?)
                    ON CONFLICT (zone, cx, cy) DO UPDATE SET n = stuck_point.n + 1, last_at = EXCLUDED.last_at""",
                    zone, x, y, at, at);
        } catch (Exception e) {
            log.warn("Не удалось записать точку упора: {}", e.getMessage());
        }
    }

    /** Точки, где упирались не меньше minN раз за последние 30 дней. */
    public List<StuckPoint> reliableStuck(int minN) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("SELECT zone, cx, cy, n FROM stuck_point WHERE n >= ? AND last_at >= now() - interval '30 days' ORDER BY n DESC LIMIT 200",
                    (rs, i) -> new StuckPoint(rs.getInt("zone"), rs.getDouble("cx"), rs.getDouble("cy"), rs.getInt("n")), minN);
        } catch (Exception e) {
            log.warn("Не удалось прочитать точки упора: {}", e.getMessage());
            return List.of();
        }
    }

    /** Смерти по клеткам 2×2 за последние hours часов. */
    public List<CellCount> deathCells(int hours) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT zone, floor(x / 2)::int * 2 + 1 AS cx, floor(y / 2)::int * 2 + 1 AS cy, count(*) AS n
                    FROM game_event WHERE type = 'death' AND x IS NOT NULL AND zone IS NOT NULL AND ts >= now() - make_interval(hours => ?)
                    GROUP BY 1, 2, 3""",
                    (rs, i) -> new CellCount(rs.getInt("zone"), rs.getInt("cx"), rs.getInt("cy"), rs.getLong("n")), hours);
        } catch (Exception e) {
            log.warn("Не удалось прочитать смерти: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Зависшие бои («бой без противника», «бой слишком долгий») по клеткам 2×2 за последние minutes минут. Считаем только записи
     * охотничьего цикла (с тире в тексте), а не дублирующую запись самого боя.
     */
    public List<CellCount> stalledFightCells(int minutes) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT zone, floor(x / 2)::int * 2 + 1 AS cx, floor(y / 2)::int * 2 + 1 AS cy, count(*) AS n
                    FROM bot_log WHERE category = 'fight' AND (message LIKE 'бой без противника —%' OR message LIKE 'бой слишком долгий —%')
                      AND x IS NOT NULL AND zone IS NOT NULL AND ts >= now() - make_interval(mins => ?)
                    GROUP BY 1, 2, 3""",
                    (rs, i) -> new CellCount(rs.getInt("zone"), rs.getInt("cx"), rs.getInt("cy"), rs.getLong("n")), minutes);
        } catch (Exception e) {
            log.warn("Не удалось прочитать зависшие бои: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Сохранить правило: если такое уже действует — продлить срок, иначе добавить новое.
     *
     * @return true, если правило новое
     */
    public boolean upsertDirective(Directive d, String status) {
        if (!isEnabled()) return false;
        try {
            Timestamp until = new Timestamp(System.currentTimeMillis() + d.ttlMinutes() * 60_000L);
            int updated = jdbc().update("""
                    UPDATE advisor_directive SET until_at = ?, reason = ?
                    WHERE kind = ? AND zone = ? AND x = ? AND y = ? AND until_at > now()""",
                    until, d.reason(), d.kind(), d.zone(), (float) d.x(), (float) d.y());
            if (updated > 0) return false;
            jdbc().update("INSERT INTO advisor_directive (created_at, kind, zone, x, y, r, until_at, reason, status) VALUES (now(), ?, ?, ?, ?, ?, ?, ?, ?)",
                    d.kind(), d.zone(), d.x(), d.y(), d.r(), until, d.reason(), status);
            return true;
        } catch (Exception e) {
            log.warn("Не удалось сохранить правило советника: {}", e.getMessage());
            return false;
        }
    }

    /** Действующие правила (срок не истёк). */
    public List<ActiveDirective> activeDirectives() {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT id, kind, zone, x, y, r, (extract(epoch FROM until_at) * 1000)::bigint AS until_ms, reason
                    FROM advisor_directive WHERE until_at > now() ORDER BY id LIMIT 200""",
                    (rs, i) -> new ActiveDirective(rs.getLong("id"), rs.getString("kind"), rs.getInt("zone"), rs.getDouble("x"), rs.getDouble("y"),
                            rs.getDouble("r"), rs.getLong("until_ms"), rs.getString("reason")));
        } catch (Exception e) {
            log.warn("Не удалось прочитать действующие правила: {}", e.getMessage());
            return List.of();
        }
    }

    /** Отметить действующие правила как применённые ботом. */
    public void markApplied() {
        if (!isEnabled()) return;
        try {
            jdbc().update("UPDATE advisor_directive SET status = 'applied' WHERE until_at > now() AND status <> 'applied'");
        } catch (Exception e) {
            log.warn("Не удалось отметить правила применёнными: {}", e.getMessage());
        }
    }

    /** Правила старше 7 дней после окончания срока не нужны. */
    public int purgeOld() {
        if (!isEnabled()) return 0;
        try {
            return jdbc().update("DELETE FROM advisor_directive WHERE until_at < now() - interval '7 days'");
        } catch (Exception e) {
            return 0;
        }
    }
}
