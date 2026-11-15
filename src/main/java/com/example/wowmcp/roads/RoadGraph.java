package com.example.wowmcp.roads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Граф дорог одной зоны в памяти: узлы, рёбра и поиск пути A*. */
public final class RoadGraph {

    public record Node(long id, double x, double y) {}

    /** Ребро a–b (a < b), без направления. */
    public record Edge(long a, long b, double cost, int fails, int ok, boolean learned) {}

    public static final RoadGraph EMPTY = new RoadGraph(List.of(), List.of());

    private final Map<Long, Node> nodes = new HashMap<>();
    private final List<Edge> edges;
    private final Map<Long, List<Edge>> adjacency = new HashMap<>();

    public RoadGraph(Collection<Node> nodeList, Collection<Edge> edgeList) {
        for (Node n : nodeList) nodes.put(n.id(), n);
        this.edges = List.copyOf(edgeList);
        for (Edge e : edges) {
            adjacency.computeIfAbsent(e.a(), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.b(), k -> new ArrayList<>()).add(e);
        }
    }

    public boolean isEmpty() {
        return nodes.isEmpty() || edges.isEmpty();
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public List<Edge> edges() {
        return edges;
    }

    public Node node(long id) {
        return nodes.get(id);
    }

    /** Ближайший узел не дальше maxDist (единицы карты) или null. */
    public Node nearest(double x, double y, double maxDist) {
        Node best = null;
        double bestD = maxDist;
        for (Node n : nodes.values()) {
            double d = Math.hypot(n.x() - x, n.y() - y);
            if (d <= bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    /** Ключ ребра для списка исключений. */
    public static String key(long a, long b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    /** Стоимость прохода по ребру: длина * (1 + fails). */
    static double weight(Edge e) {
        return e.cost() * (1 + e.fails());
    }

    /**
     * A* от узла from до узла to. excluded — ключи рёбер ({@link #key}), по которым ходить нельзя.
     * Возвращает узлы пути включая оба конца или null, если пути нет.
     */
    public List<Node> shortestPath(long from, long to, Set<String> excluded) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) return null;
        Node goal = nodes.get(to);
        Map<Long, Double> g = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        record Item(long id, double f) {}
        PriorityQueue<Item> open = new PriorityQueue<>((p, q) -> Double.compare(p.f(), q.f()));
        g.put(from, 0.0);
        open.add(new Item(from, heuristic(nodes.get(from), goal)));
        while (!open.isEmpty()) {
            Item cur = open.poll();
            double gc = g.get(cur.id());
            if (cur.f() > gc + heuristic(nodes.get(cur.id()), goal) + 1e-9) continue;   // устаревшая запись
            if (cur.id() == to) {
                List<Node> path = new ArrayList<>();
                for (Long id = to; id != null; id = prev.get(id)) path.add(nodes.get(id));
                Collections.reverse(path);
                return path;
            }
            for (Edge e : adjacency.getOrDefault(cur.id(), List.of())) {
                if (excluded != null && excluded.contains(key(e.a(), e.b()))) continue;
                long next = e.a() == cur.id() ? e.b() : e.a();
                double ng = gc + weight(e);
                if (ng < g.getOrDefault(next, Double.MAX_VALUE)) {
                    g.put(next, ng);
                    prev.put(next, cur.id());
                    open.add(new Item(next, ng + heuristic(nodes.get(next), goal)));
                }
            }
        }
        return null;
    }

    private static double heuristic(Node a, Node b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    public static double length(List<Node> path) {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            sum += Math.hypot(path.get(i).x() - path.get(i - 1).x(), path.get(i).y() - path.get(i - 1).y());
        }
        return sum;
    }
}
