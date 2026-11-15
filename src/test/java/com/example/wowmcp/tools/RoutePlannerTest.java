package com.example.wowmcp.tools;

import com.example.wowmcp.db.RouteRepository.Point;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePlannerTest {

    private static Point p(double x, double y) {
        return new Point(117, x, y);
    }

    /** Прямая вдоль X с шагом 0.3: 0.0 .. 12.0. */
    private static List<Point> line() {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i <= 40; i++) pts.add(p(i * 0.3, 0));
        return pts;
    }

    @Test
    void thinKeepsStepAndEnds() {
        List<Point> thin = RoutePlanner.thin(line());
        assertThat(thin.get(0)).isEqualTo(p(0, 0));
        assertThat(thin.get(thin.size() - 1).x()).isEqualTo(12.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(thin.size()).isBetween(7, 11);                     // ~12 / 1.5 + 1
        for (int i = 1; i < thin.size() - 1; i++) {
            assertThat(RoutePlanner.dist(thin.get(i), thin.get(i - 1).x(), thin.get(i - 1).y())).isGreaterThanOrEqualTo(1.5 - 1e-9);
        }
    }

    @Test
    void thinKeepsTurnsOver25Degrees() {
        // вдоль X до 3.0, затем резкий поворот на 90° вверх: точка поворота (3.0; 0) должна остаться
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i <= 10; i++) pts.add(p(i * 0.3, 0));
        for (int i = 1; i <= 10; i++) pts.add(p(3.0, i * 0.3));
        List<Point> thin = RoutePlanner.thin(pts);
        // угол срезается не больше чем на ~0.6 ед. — меньше допуска точки при проходе (0.6)
        assertThat(thin).anyMatch(q -> Math.hypot(q.x() - 3.0, q.y()) <= 0.7);
        assertThat(thin.size()).isLessThan(pts.size() / 2);
    }

    @Test
    void thinDoesNotKeepGentleDriftOrNoise() {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i <= 40; i++) pts.add(p(i * 0.3, (i % 2) * 0.05));   // дрожание ±0.05
        assertThat(RoutePlanner.thin(pts).size()).isLessThanOrEqualTo(12);
    }

    @Test
    void orientGoesAwayFromPlayerAndStartsAtNearestPoint() {
        List<Point> route = RoutePlanner.thin(line());
        // стоим у конца (x ≈ 12): идти «от меня» = обратно
        List<Point> seq = RoutePlanner.orient(route, 11.8, 0, null);
        assertThat(seq.get(0).x()).isGreaterThan(10.0);
        assertThat(seq.get(seq.size() - 1).x()).isEqualTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        // стоим посередине: начинаем с ближайшей точки, конец — самый дальний
        List<Point> mid = RoutePlanner.orient(route, 6.0, 0, null);
        assertThat(mid.get(0).x()).isBetween(4.5, 7.5);
        // явное направление
        List<Point> forced = RoutePlanner.orient(route, 6.0, 0, true);
        assertThat(forced.get(forced.size() - 1).x()).isEqualTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void fitsPlaceInEitherDirection() {
        List<Point> route = RoutePlanner.thin(line());   // 0..12 по X
        assertThat(RoutePlanner.fitsPlace(route, 1.0, 0.5, 11.0, 0.0)).isFalse();     // прямое
        assertThat(RoutePlanner.fitsPlace(route, 11.0, 0.5, 1.0, 0.0)).isTrue();      // обратное
        assertThat(RoutePlanner.fitsPlace(route, 6.0, 0.0, 11.0, 0.0)).isNull();      // старт не рядом с игроком
        assertThat(RoutePlanner.fitsPlace(route, 1.0, 0.0, 6.0, 0.0)).isNull();       // конец не рядом с местом
        assertThat(RoutePlanner.fitsPlace(List.of(p(0, 0)), 0, 0, 0, 0)).isNull();
    }

    @Test
    void namesNormalize() {
        assertThat(Names.normalize("  Перекрёсток ")).isEqualTo("перекресток");
        assertThat(Names.normalize("Лагерь   Таурахо")).isEqualTo("лагерь таурахо");
        assertThat(Names.normalize(null)).isEmpty();
    }
}
