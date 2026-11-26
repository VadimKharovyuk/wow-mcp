package com.example.wowmcp.spots;

import com.example.wowmcp.db.BaseRepository;
import com.example.wowmcp.db.Database;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Только чтение: снимки опыта, убийства, смерти и упоры за период. В снимках опыта иногда лежит мусор (сбой чтения из памяти игры,
 * число вида 0.000…1 или из сотен цифр): такие строки отбрасываются, а не роняют запрос. Нет БД — пустые списки.
 */
@Repository
public class SpotRepository extends BaseRepository {

    public SpotRepository(Database db) {
        super(db);
    }

    public List<Snapshot> snapshots(int hours) {
        if (!isEnabled()) return List.of();
        return jdbc().query("""
                SELECT extract(epoch FROM ts)::bigint AS ts, (data->>'level')::int AS level,
                       (data->>'xp')::bigint AS xp,
                       CASE WHEN data->>'xpMax' ~ '^[0-9]{1,12}$' THEN (data->>'xpMax')::bigint ELSE 0 END AS xp_max
                FROM game_event WHERE type = 'stat' AND ts >= now() - make_interval(hours => ?)
                  AND data->>'level' ~ '^[0-9]{1,3}$' AND data->>'xp' ~ '^[0-9]{1,12}$'
                ORDER BY ts""", (rs, i) -> new Snapshot(rs.getLong("ts"), rs.getInt("level"), rs.getLong("xp"), rs.getLong("xp_max")), hours);
    }

    public List<Kill> kills(int hours) {
        if (!isEnabled()) return List.of();
        return jdbc().query("""
                SELECT extract(epoch FROM killed_at)::bigint AS ts, zone, x, y, mob_level, player_level, source
                FROM mob_kill WHERE killed_at >= now() - make_interval(hours => ?)""",
                (rs, i) -> new Kill(rs.getLong("ts"), rs.getInt("zone"), rs.getDouble("x"), rs.getDouble("y"),
                        (Integer) rs.getObject("mob_level"), (Integer) rs.getObject("player_level"), "bot".equals(rs.getString("source"))), hours);
    }

    public List<MapPoint> deaths(int hours) {
        if (!isEnabled()) return List.of();
        return jdbc().query("SELECT zone, x, y FROM game_event WHERE type = 'death' AND x IS NOT NULL AND zone IS NOT NULL "
                + "AND ts >= now() - make_interval(hours => ?)", (rs, i) -> new MapPoint(rs.getInt("zone"), rs.getDouble("x"), rs.getDouble("y")), hours);
    }

    /** Упоры из журнала бота (хранится 10 дней). */
    public List<MapPoint> stucks(int hours) {
        if (!isEnabled()) return List.of();
        return jdbc().query("SELECT zone, x, y FROM bot_log WHERE category = 'stuck' AND x IS NOT NULL AND zone IS NOT NULL "
                + "AND ts >= now() - make_interval(hours => ?)", (rs, i) -> new MapPoint(rs.getInt("zone"), rs.getDouble("x"), rs.getDouble("y")), hours);
    }
}
