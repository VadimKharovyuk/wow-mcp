package com.example.wowmcp.roads;

import com.example.wowmcp.db.RoadRepository;
import com.example.wowmcp.roads.RoadGraph.Edge;
import com.example.wowmcp.roads.RoadGraph.Node;
import com.example.wowmcp.roads.RoadGraphBuilder.Built;
import com.example.wowmcp.roads.RoadGraphBuilder.TrackPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoadsTest {

    /** Прямая дорога вдоль X из точек через 0.3 ед., по секунде на точку. */
    private static List<TrackPoint> line(String session, String source, double x0, double x1, double y, long t0, long dtMs) {
        List<TrackPoint> pts = new ArrayList<>();
        long t = t0;
        for (double x = x0; x <= x1 + 1e-9; x += 0.3) {
            pts.add(new TrackPoint(session, source, x, y, t));
            t += dtMs;
        }
        return pts;
    }

    @Test
    void thinsTrackWithStep08AndLinksNeighbours() {
        Built b = RoadGraphBuilder.build(line("s1", "manual", 0, 8, 0, 0, 1000));
        assertThat(b.nodes().size()).isBetween(9, 12);                     // 8 / 0.8 + 1
        assertThat(b.edgeIdx().size()).isEqualTo(b.nodes().size() - 1);    // цепочка
    }

    @Test
    void mergesNodesOfDifferentTracksCloserThan06IntoNetwork() {
        List<TrackPoint> pts = new ArrayList<>(line("a", "manual", 0, 8, 0, 0, 1000));
        // вторая дорога пересекает первую в x≈4 (ось Y), одна точка пересечения общая
        for (double y = -4; y <= 4 + 1e-9; y += 0.3) pts.add(new TrackPoint("b", "player", 4.1, y, (long) ((y + 4) * 1000)));
        Built b = RoadGraphBuilder.build(pts);
        // узел на пересечении имеет степень ≥ 3
        int[] degree = new int[b.nodes().size()];
        for (int[] e : b.edgeIdx()) { degree[e[0]]++; degree[e[1]]++; }
        assertThat(java.util.Arrays.stream(degree).max().getAsInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void botTrackWithStallOver3sIsSplitAndStallPartDropped() {
        List<TrackPoint> pts = new ArrayList<>();
        pts.addAll(line("bot1", "bot", 0, 3, 0, 0, 1000));            // участок 1: 0..3
        pts.addAll(line("bot1", "bot", 3.3, 6, 0, 20_000, 1000));     // пауза 17 с (застрял), участок 2: 3.3..6
        Built b = RoadGraphBuilder.build(pts);
        // разрыв между x=3 и x=3.3 — разных участков — ребром не соединён
        boolean bridged = false;
        for (int[] e : b.edgeIdx()) {
            double xa = b.nodes().get(e[0])[0], xb = b.nodes().get(e[1])[0];
            if (Math.min(xa, xb) <= 3.0 && Math.max(xa, xb) >= 3.3) bridged = true;
        }
        assertThat(bridged).isFalse();
        // тот же трек от игрока (не bot) не режется
        List<TrackPoint> player = new ArrayList<>();
        player.addAll(line("p1", "player", 0, 3, 0, 0, 1000));
        player.addAll(line("p1", "player", 3.3, 6, 0, 20_000, 1000));
        assertThat(RoadGraphBuilder.split(player)).hasSize(1);
    }

    @Test
    void holesInRecordingAreNotRoads() {
        List<TrackPoint> pts = new ArrayList<>();
        pts.add(new TrackPoint("s", "manual", 0, 0, 0));
        pts.add(new TrackPoint("s", "manual", 10, 0, 1000));            // телепорт/дыра 10 ед.
        assertThat(RoadGraphBuilder.build(pts).edgeIdx()).isEmpty();
    }

    private static RoadGraph square() {
        // 1 -(2)- 2, 2 -(2)- 3, 1 -(10)- 3 (короткий путь 1→2→3 = 4 против 10)
        List<Node> nodes = List.of(new Node(1, 0, 0), new Node(2, 2, 0), new Node(3, 4, 0));
        List<Edge> edges = List.of(new Edge(1, 2, 2, 0, 0, false), new Edge(2, 3, 2, 0, 0, false), new Edge(1, 3, 10, 0, 0, false));
        return new RoadGraph(nodes, edges);
    }

    @Test
    void astarFindsShortestAndAvoidsFailedEdges() {
        RoadGraph g = square();
        assertThat(g.shortestPath(1, 3, null).stream().map(Node::id)).containsExactly(1L, 2L, 3L);
        // исключённое ребро 2-3 заставляет идти длинным 1-3
        assertThat(g.shortestPath(1, 3, Set.of(RoadGraph.key(2, 3))).stream().map(Node::id)).containsExactly(1L, 3L);
        // fails удорожают: ребро 1-2 с fails=9 стоит 2*10=20 > 10
        RoadGraph failing = new RoadGraph(g.nodes(), List.of(new Edge(1, 2, 2, 9, 0, false), new Edge(2, 3, 2, 0, 0, false), new Edge(1, 3, 10, 0, 0, false)));
        assertThat(failing.shortestPath(1, 3, null).stream().map(Node::id)).containsExactly(1L, 3L);
        assertThat(g.shortestPath(1, 99, null)).isNull();
    }

    @Test
    void planUsesRoadsOnlyWhenTheyHelp() {
        RoadRepository repo = mock(RoadRepository.class);
        // дорога вдоль X от 0 до 40
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i <= 20; i++) nodes.add(new Node(i + 1, i * 2.0, 0));
        for (int i = 1; i <= 20; i++) edges.add(new Edge(i, i + 1, 2, 0, 0, false));
        when(repo.nodes(117)).thenReturn(nodes);
        when(repo.edges(117)).thenReturn(edges);
        RoadService service = new RoadService(repo);

        // старт и цель у дороги → по дорогам: последняя точка — цель, остальные узлы
        Optional<RoadService.Plan> plan = service.plan(117, 1.0, 2.0, 38.0, 3.0);
        assertThat(plan).isPresent();
        assertThat(plan.get().points().get(plan.get().points().size() - 1).node()).isFalse();
        assertThat(plan.get().points().get(0).node()).isTrue();
        // слишком близко (< 8 ед.) — прямо
        assertThat(service.plan(117, 1.0, 2.0, 6.0, 3.0)).isEmpty();
        // старт далеко от любого узла (> 6) — прямо
        assertThat(service.plan(117, 1.0, 20.0, 38.0, 3.0)).isEmpty();
        // зона без графа — прямо
        assertThat(service.plan(104, 1.0, 2.0, 38.0, 3.0)).isEmpty();
    }

    @Test
    void planFallsBackToDirectWhenDetourIsTooLong() {
        RoadRepository repo = mock(RoadRepository.class);
        // дорога-крюк: A(0,0) → (0,20) → (20,20) → B(20,0); напрямую A→B = 20, по дороге = 60 > 2.5 * 20
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        double[][] xy = {{0, 0}, {0, 5}, {0, 10}, {0, 15}, {0, 20}, {5, 20}, {10, 20}, {15, 20}, {20, 20}, {20, 15}, {20, 10}, {20, 5}, {20, 0}};
        for (int i = 0; i < xy.length; i++) nodes.add(new Node(i + 1, xy[i][0], xy[i][1]));
        for (int i = 1; i < xy.length; i++) edges.add(new Edge(i, i + 1, 5, 0, 0, false));
        when(repo.nodes(1)).thenReturn(nodes);
        when(repo.edges(1)).thenReturn(edges);
        assertThat(new RoadService(repo).plan(1, 0, 0, 20, 0)).isEmpty();
    }
}
