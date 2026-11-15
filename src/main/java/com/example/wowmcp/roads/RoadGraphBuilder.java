package com.example.wowmcp.roads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Строит граф дорог зоны из записанных треков: прореживание (шаг 0.8), слияние узлов разных записей
 * ближе 0.6 ед., рёбра между соседними точками записи. Участки записи бота с остановкой дольше 3 с
 * (застревание) в граф не попадают.
 */
public final class RoadGraphBuilder {

    /** Точка записанного пути. */
    public record TrackPoint(String session, String source, double x, double y, long atMillis) {}

    /** Узел/ребро до присвоения id: узлы нумеруются с 0 по порядку создания. */
    public record Built(List<double[]> nodes, List<int[]> edgeIdx, List<Double> edgeCost) {}

    static final double STEP = 0.8;
    static final double MERGE = 0.6;
    static final long BOT_STALL_MS = 3000;
    /** Ребро длиннее — дыра в записи, а не дорога. */
    static final double MAX_EDGE = 4.0;

    private RoadGraphBuilder() {
    }

    /** Точки одной зоны, отсортированные по (session, время). */
    public static Built build(List<TrackPoint> points) {
        Map<String, List<TrackPoint>> tracks = new LinkedHashMap<>();
        for (TrackPoint p : points) tracks.computeIfAbsent(p.session(), k -> new ArrayList<>()).add(p);

        List<double[]> nodes = new ArrayList<>();
        Map<String, Double> edges = new LinkedHashMap<>();
        for (List<TrackPoint> track : tracks.values()) {
            for (List<TrackPoint> segment : split(track)) {
                int prev = -1;
                double lastX = Double.NaN, lastY = Double.NaN;
                for (int i = 0; i < segment.size(); i++) {
                    TrackPoint p = segment.get(i);
                    boolean first = i == 0, last = i == segment.size() - 1;
                    if (!first && !last && Math.hypot(p.x() - lastX, p.y() - lastY) < STEP) continue;
                    lastX = p.x();
                    lastY = p.y();
                    int id = nodeFor(nodes, p.x(), p.y());
                    if (prev >= 0 && prev != id) {
                        double[] a = nodes.get(prev), b = nodes.get(id);
                        double len = Math.hypot(a[0] - b[0], a[1] - b[1]);
                        if (len <= MAX_EDGE) edges.putIfAbsent(Math.min(prev, id) + ":" + Math.max(prev, id), len);
                    }
                    prev = id;
                }
            }
        }
        List<int[]> idx = new ArrayList<>();
        List<Double> cost = new ArrayList<>();
        for (Map.Entry<String, Double> e : edges.entrySet()) {
            String[] ab = e.getKey().split(":");
            idx.add(new int[]{Integer.parseInt(ab[0]), Integer.parseInt(ab[1])});
            cost.add(e.getValue());
        }
        return new Built(nodes, idx, cost);
    }

    /** Трек бота делится на участки там, где между точками > 3 с (стоял/застрял/дрался). Остальные — целиком. */
    static List<List<TrackPoint>> split(List<TrackPoint> track) {
        if (track.isEmpty()) return List.of();
        if (!"bot".equals(track.get(0).source())) return List.of(track);
        List<List<TrackPoint>> out = new ArrayList<>();
        List<TrackPoint> cur = new ArrayList<>();
        for (TrackPoint p : track) {
            if (!cur.isEmpty() && p.atMillis() - cur.get(cur.size() - 1).atMillis() > BOT_STALL_MS) {
                if (cur.size() > 1) out.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(p);
        }
        if (cur.size() > 1) out.add(cur);
        return out;
    }

    /** Индекс существующего узла ближе MERGE или новый узел. */
    static int nodeFor(List<double[]> nodes, double x, double y) {
        int best = -1;
        double bestD = MERGE;
        for (int i = 0; i < nodes.size(); i++) {
            double d = Math.hypot(nodes.get(i)[0] - x, nodes.get(i)[1] - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0) return best;
        nodes.add(new double[]{x, y});
        return nodes.size() - 1;
    }
}
