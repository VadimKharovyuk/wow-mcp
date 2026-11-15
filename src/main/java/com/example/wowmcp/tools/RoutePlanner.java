package com.example.wowmcp.tools;

import com.example.wowmcp.db.RouteRepository.Point;

import java.util.ArrayList;
import java.util.List;

/** Чистая логика маршрутов: прореживание записи, выбор направления и стартовой точки. */
final class RoutePlanner {

    /** Шаг прореживания, единицы карты. */
    static final double STEP = 1.5;
    /** Поворот, который нельзя терять при прореживании, градусы. */
    static final double TURN_DEG = 25;
    /** Насколько близко маршрут должен начинаться/кончаться от игрока и места, единицы карты. */
    static final double NEAR = 3.0;

    private RoutePlanner() {
    }

    static double dist(Point a, double x, double y) {
        return Math.hypot(a.x() - x, a.y() - y);
    }

    /**
     * Оставляет точки с шагом не меньше {@link #STEP} и все точки поворота больше {@link #TURN_DEG}.
     * Первая и последняя точки сохраняются. Мелкий шум (< 0.5 ед.) на углы не влияет.
     */
    static List<Point> thin(List<Point> points) {
        if (points.size() <= 2) return new ArrayList<>(points);
        List<Point> out = new ArrayList<>();
        out.add(points.get(0));
        Point last = points.get(0);
        for (int i = 1; i < points.size() - 1; i++) {
            Point p = points.get(i);
            double fromLast = dist(last, p.x(), p.y());
            boolean keep = fromLast >= STEP;
            if (!keep && fromLast >= 0.5) {
                Point next = null;
                for (int j = i + 1; j < points.size(); j++) {
                    if (dist(p, points.get(j).x(), points.get(j).y()) >= 0.5) { next = points.get(j); break; }
                }
                if (next != null) {
                    double a1 = Math.atan2(p.y() - last.y(), p.x() - last.x());
                    double a2 = Math.atan2(next.y() - p.y(), next.x() - p.x());
                    double diff = Math.abs(Math.toDegrees(Math.atan2(Math.sin(a2 - a1), Math.cos(a2 - a1))));
                    keep = diff > TURN_DEG;
                }
            }
            if (keep) {
                out.add(p);
                last = p;
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    /**
     * Точки маршрута в порядке прохождения. reverse == null — идти «от меня»: выбирается направление,
     * чей конец дальше от игрока. Идти начинаем с ближайшей к игроку точки, а не с начала.
     */
    static List<Point> orient(List<Point> points, double px, double py, Boolean reverse) {
        if (points.isEmpty()) return List.of();
        boolean rev;
        if (reverse != null) {
            rev = reverse;
        } else {
            double toFirst = dist(points.get(0), px, py), toLast = dist(points.get(points.size() - 1), px, py);
            rev = toFirst > toLast;      // конец прямого направления (последняя точка) ближе — идём обратно
        }
        List<Point> seq = new ArrayList<>(points);
        if (rev) java.util.Collections.reverse(seq);
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < seq.size(); i++) {
            double d = dist(seq.get(i), px, py);
            if (d < best) { best = d; nearest = i; }
        }
        return new ArrayList<>(seq.subList(nearest, seq.size()));
    }

    /**
     * Подходит ли маршрут для go_place: начинается рядом с игроком и кончается рядом с местом
     * (в любом направлении). Возвращает направление (false — прямое, true — обратное) или null.
     */
    static Boolean fitsPlace(List<Point> points, double px, double py, double tx, double ty) {
        if (points.size() < 2) return null;
        Point first = points.get(0), last = points.get(points.size() - 1);
        if (dist(first, px, py) <= NEAR && dist(last, tx, ty) <= NEAR) return false;
        if (dist(last, px, py) <= NEAR && dist(first, tx, ty) <= NEAR) return true;
        return null;
    }

    static double length(List<Point> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            sum += Math.hypot(points.get(i).x() - points.get(i - 1).x(), points.get(i).y() - points.get(i - 1).y());
        }
        return sum;
    }
}
