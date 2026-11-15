package com.example.wowmcp.db;

import com.example.wowmcp.roads.RoadGraph.Edge;
import com.example.wowmcp.roads.RoadGraph.Node;
import com.example.wowmcp.roads.RoadGraphBuilder.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Граф дорог в БД (road_node, road_edge) и треки для его построения (path_point). */
@Component
public class RoadRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(RoadRepository.class);

    public RoadRepository(Database db) {
        super(db);
    }

    /** Точки manual/player/bot по зонам, по порядку записи. */
    public Map<Integer, List<TrackPoint>> loadTracks() {
        Map<Integer, List<TrackPoint>> byZone = new LinkedHashMap<>();
        if (!isEnabled()) return byZone;
        try {
            jdbc().query("""
                    SELECT zone, session_id, source, x, y, recorded_at FROM path_point
                    WHERE session_id IS NOT NULL AND zone <> 0
                    ORDER BY zone, session_id, recorded_at, id""", rs -> {
                byZone.computeIfAbsent(rs.getInt("zone"), k -> new ArrayList<>()).add(new TrackPoint(
                        rs.getString("session_id"), rs.getString("source"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getTimestamp("recorded_at").getTime()));
            });
        } catch (Exception e) {
            log.warn("Не удалось прочитать треки: {}", e.getMessage());
        }
        return byZone;
    }

    public List<Integer> zonesWithGraph() {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().queryForList("SELECT DISTINCT zone FROM road_node ORDER BY zone", Integer.class);
        } catch (Exception e) {
            log.warn("Не удалось прочитать зоны графа: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Node> nodes(int zone) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("SELECT id, x, y FROM road_node WHERE zone = ? ORDER BY id",
                    (rs, i) -> new Node(rs.getLong("id"), rs.getDouble("x"), rs.getDouble("y")), zone);
        } catch (Exception e) {
            log.warn("Не удалось прочитать узлы: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Edge> edges(int zone) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT e.a, e.b, e.cost, e.fails, e.ok, e.learned FROM road_edge e
                    JOIN road_node n ON n.id = e.a WHERE n.zone = ?""",
                    (rs, i) -> new Edge(rs.getLong("a"), rs.getLong("b"), rs.getDouble("cost"),
                            rs.getInt("fails"), rs.getInt("ok"), rs.getBoolean("learned")), zone);
        } catch (Exception e) {
            log.warn("Не удалось прочитать рёбра: {}", e.getMessage());
            return List.of();
        }
    }

    /** Заменить граф зоны целиком в одной транзакции. Узлы получают id из общей последовательности. */
    public boolean replaceGraph(int zone, List<double[]> nodes, List<int[]> edgeIdx, List<Double> cost,
                                List<int[]> counters, List<Boolean> learned) {
        if (!isEnabled()) return false;
        try {
            db.inTransaction(() -> {
                jdbc().update("DELETE FROM road_edge WHERE a IN (SELECT id FROM road_node WHERE zone = ?)", zone);
                jdbc().update("DELETE FROM road_node WHERE zone = ?", zone);
                Long max = jdbc().queryForObject("SELECT coalesce(max(id), 0) FROM road_node", Long.class);
                long base = (max == null ? 0 : max) + 1;
                for (int i = 0; i < nodes.size(); i++) {
                    jdbc().update("INSERT INTO road_node (id, zone, x, y) VALUES (?,?,?,?)",
                            base + i, zone, nodes.get(i)[0], nodes.get(i)[1]);
                }
                for (int i = 0; i < edgeIdx.size(); i++) {
                    long a = base + Math.min(edgeIdx.get(i)[0], edgeIdx.get(i)[1]);
                    long b = base + Math.max(edgeIdx.get(i)[0], edgeIdx.get(i)[1]);
                    jdbc().update("INSERT INTO road_edge (a, b, cost, fails, ok, learned) VALUES (?,?,?,?,?,?)",
                            a, b, cost.get(i), counters.get(i)[0], counters.get(i)[1], learned.get(i));
                }
            });
            return true;
        } catch (Exception e) {
            log.warn("Не удалось сохранить граф зоны {}: {}", zone, e.getMessage());
            return false;
        }
    }

    /** Прибавить fails или ok ребру между узлами a и b. */
    public void bumpEdge(long a, long b, boolean ok) {
        if (!isEnabled()) return;
        try {
            jdbc().update("UPDATE road_edge SET " + (ok ? "ok = ok + 1" : "fails = fails + 1") + " WHERE a = ? AND b = ?",
                    Math.min(a, b), Math.max(a, b));
        } catch (Exception e) {
            log.warn("Не удалось обновить ребро: {}", e.getMessage());
        }
    }

    /** Добавить выученное ребро (граф растёт сам); узлы должны существовать. */
    public void addLearnedEdge(long a, long b, double cost) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO road_edge (a, b, cost, learned) VALUES (?,?,?,true)
                    ON CONFLICT (a, b) DO NOTHING""", Math.min(a, b), Math.max(a, b), cost);
        } catch (Exception e) {
            log.warn("Не удалось добавить ребро: {}", e.getMessage());
        }
    }

    /** Новый узел; возвращает его id. */
    public long addNode(int zone, double x, double y) {
        if (!isEnabled()) return -1;
        try {
            Long max = jdbc().queryForObject("SELECT coalesce(max(id), 0) FROM road_node", Long.class);
            long id = (max == null ? 0 : max) + 1;
            jdbc().update("INSERT INTO road_node (id, zone, x, y) VALUES (?,?,?,?)", id, zone, x, y);
            return id;
        } catch (Exception e) {
            log.warn("Не удалось добавить узел: {}", e.getMessage());
            return -1;
        }
    }

    /** Точки бота старше 7 дней удаляем: в граф они уже вошли (или это мусор). */
    public int cleanupBotPoints() {
        if (!isEnabled()) return 0;
        try {
            return jdbc().update("DELETE FROM path_point WHERE source = 'bot' AND recorded_at < now() - interval '7 days'");
        } catch (Exception e) {
            log.warn("Не удалось почистить точки бота: {}", e.getMessage());
            return 0;
        }
    }
}
