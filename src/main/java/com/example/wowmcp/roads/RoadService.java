package com.example.wowmcp.roads;

import com.example.wowmcp.db.RoadRepository;
import com.example.wowmcp.roads.RoadGraph.Edge;
import com.example.wowmcp.roads.RoadGraph.Node;
import com.example.wowmcp.roads.RoadGraphBuilder.Built;
import com.example.wowmcp.roads.RoadGraphBuilder.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Граф дорог: построение из записей, маршрут A*, обучение (застревания и новые рёбра). */
@Component
public class RoadService {

    private static final Logger log = LoggerFactory.getLogger(RoadService.class);

    /** Дальше этого от узла дорогу не используем: идём прямо. */
    static final double NEAR_NODE = 6.0;
    /** Путь по дорогам длиннее прямого больше чем в это число раз — идём прямо. */
    static final double MAX_DETOUR = 2.5;
    /** Короткие переходы дорогами не планируем. */
    static final double MIN_DIRECT = 8.0;
    /** Старые рёбра при перестроении привязываем к новым узлам ближе этого расстояния. */
    private static final double CARRY = 0.3;

    /** Точка маршрута для бота; node — узел графа (по рёбрам между узлами учим fails/ok). */
    public record Waypoint(double x, double y, boolean node) {}

    /** Маршрут: точки по порядку (последняя — цель), длина по дорогам и прямая. */
    public record Plan(List<Waypoint> points, int nodes, double roadLength, double directLength) {}

    private final RoadRepository repo;
    private final Map<Integer, RoadGraph> cache = new ConcurrentHashMap<>();

    public RoadService(RoadRepository repo) {
        this.repo = repo;
    }

    public RoadGraph graph(int zone) {
        return cache.computeIfAbsent(zone, z -> new RoadGraph(repo.nodes(z), repo.edges(z)));
    }

    /** Маршрут по дорогам или empty — тогда идти прямо (граф пуст, дорога далеко, объезд слишком длинный). */
    public Optional<Plan> plan(int zone, double fx, double fy, double tx, double ty) {
        double direct = Math.hypot(tx - fx, ty - fy);
        if (direct < MIN_DIRECT) return Optional.empty();
        RoadGraph g = graph(zone);
        if (g.isEmpty()) return Optional.empty();
        Node from = g.nearest(fx, fy, NEAR_NODE), to = g.nearest(tx, ty, NEAR_NODE);
        if (from == null || to == null) return Optional.empty();
        List<Node> path = g.shortestPath(from.id(), to.id(), null);
        if (path == null) return Optional.empty();
        double total = Math.hypot(path.get(0).x() - fx, path.get(0).y() - fy) + RoadGraph.length(path)
                + Math.hypot(tx - path.get(path.size() - 1).x(), ty - path.get(path.size() - 1).y());
        if (total > MAX_DETOUR * direct) return Optional.empty();
        List<Waypoint> pts = new ArrayList<>();
        for (Node n : path) pts.add(new Waypoint(n.x(), n.y(), true));
        pts.add(new Waypoint(tx, ty, false));
        log.info("Маршрут по дорогам: {} узлов, по дорогам {} / прямо {}", path.size(), round(total), round(direct));
        return Optional.of(new Plan(pts, path.size(), total, direct));
    }

    /** Результат прохода ребра между узлами (координаты узлов): ok++ или fails++. */
    public void legResult(int zone, double ax, double ay, double bx, double by, boolean ok) {
        RoadGraph g = graph(zone);
        Node a = g.nearest(ax, ay, 0.05), b = g.nearest(bx, by, 0.05);
        if (a == null || b == null || a.id() == b.id()) return;
        repo.bumpEdge(a.id(), b.id(), ok);
        cache.remove(zone);
        if (!ok) log.warn("Застряли на ребре дороги ({}, {}) → ({}, {}): fails++", ax, ay, bx, by);
    }

    /** Успешно пройденный прямой участок рядом с дорогой становится ребром (граф растёт сам). */
    public void learnEdge(int zone, double ax, double ay, double bx, double by) {
        if (Math.hypot(ax - bx, ay - by) > NEAR_NODE) return;
        RoadGraph g = graph(zone);
        long a = nodeAt(g, zone, ax, ay), b = nodeAt(g, zone, bx, by);
        if (a < 0 || b < 0 || a == b) return;
        repo.addLearnedEdge(a, b, Math.hypot(ax - bx, ay - by));
        cache.remove(zone);
        log.info("Выучено ребро дороги ({}, {}) → ({}, {})", ax, ay, bx, by);
    }

    private long nodeAt(RoadGraph g, int zone, double x, double y) {
        Node n = g.nearest(x, y, 0.6);
        return n != null ? n.id() : repo.addNode(zone, x, y);
    }

    /**
     * Перестроить граф по всем записям (manual, player — целиком; bot — без остановок). Счётчики fails/ok и
     * выученные рёбра переносятся на новые узлы. Возвращает по зонам число узлов/рёбер.
     */
    public Map<Integer, Map<String, Object>> build() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<TrackPoint>> z : repo.loadTracks().entrySet()) {
            int zone = z.getKey();
            Built built = RoadGraphBuilder.build(z.getValue());
            List<int[]> counters = new ArrayList<>();
            List<Boolean> learned = new ArrayList<>();
            for (int i = 0; i < built.edgeIdx().size(); i++) {
                counters.add(new int[]{0, 0});
                learned.add(false);
            }
            carryOver(zone, built, counters, learned);
            if (repo.replaceGraph(zone, built.nodes(), built.edgeIdx(), built.edgeCost(), counters, learned)) {
                cache.remove(zone);
                result.put(zone, Map.of("nodes", built.nodes().size(), "edges", built.edgeIdx().size(),
                        "points", z.getValue().size()));
            }
        }
        int cleaned = repo.cleanupBotPoints();
        if (cleaned > 0) log.info("Удалено точек бота старше 7 дней: {}", cleaned);
        return result;
    }

    /** Старые fails/ok и выученные рёбра — на новый граф (по близости концов, 0.3 ед.). */
    private void carryOver(int zone, Built built, List<int[]> counters, List<Boolean> learned) {
        List<Node> oldNodes = repo.nodes(zone);
        if (oldNodes.isEmpty()) return;
        Map<Long, Node> oldById = new HashMap<>();
        for (Node n : oldNodes) oldById.put(n.id(), n);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < built.edgeIdx().size(); i++) {
            int[] e = built.edgeIdx().get(i);
            index.put(Math.min(e[0], e[1]) + ":" + Math.max(e[0], e[1]), i);
        }
        for (Edge old : repo.edges(zone)) {
            if (old.fails() == 0 && old.ok() == 0 && !old.learned()) continue;
            Node pa = oldById.get(old.a()), pb = oldById.get(old.b());
            if (pa == null || pb == null) continue;
            int a = nearestIdx(built.nodes(), pa.x(), pa.y());
            int b = nearestIdx(built.nodes(), pb.x(), pb.y());
            if (old.learned()) {                       // выученный узел мог не попасть в новый граф — создаём
                if (a < 0) { built.nodes().add(new double[]{pa.x(), pa.y()}); a = built.nodes().size() - 1; }
                if (b < 0) { built.nodes().add(new double[]{pb.x(), pb.y()}); b = built.nodes().size() - 1; }
            }
            if (a < 0 || b < 0 || a == b) continue;
            String key = Math.min(a, b) + ":" + Math.max(a, b);
            Integer i = index.get(key);
            if (i == null) {
                if (!old.learned()) continue;
                built.edgeIdx().add(new int[]{Math.min(a, b), Math.max(a, b)});
                built.edgeCost().add(old.cost());
                counters.add(new int[]{old.fails(), old.ok()});
                learned.add(true);
                index.put(key, built.edgeIdx().size() - 1);
            } else {
                counters.get(i)[0] = old.fails();
                counters.get(i)[1] = old.ok();
                if (old.learned()) learned.set(i, true);
            }
        }
    }

    private static int nearestIdx(List<double[]> nodes, double x, double y) {
        int best = -1;
        double bestD = CARRY;
        for (int i = 0; i < nodes.size(); i++) {
            double d = Math.hypot(nodes.get(i)[0] - x, nodes.get(i)[1] - y);
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** Раз в сутки: убрать старые точки бота. */
    @Scheduled(fixedDelay = 24 * 3600 * 1000L, initialDelay = 3600 * 1000L)
    public void dailyCleanup() {
        int n = repo.cleanupBotPoints();
        if (n > 0) log.info("Удалено точек бота старше 7 дней: {}", n);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
