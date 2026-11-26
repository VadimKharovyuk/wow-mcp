package com.example.wowmcp.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Как правила советника влияют на места охоты (то же правило, что в JS-боте: {@code directiveFactor}): клетка из запрета даёт 0 (выбирать
 * нельзя), из штрафа — 0,5, иначе 1. Точки упора (avoid_point) места охоты не меняют — их обходит сам бот на бегу.
 */
public final class DirectiveFilter {

    private DirectiveFilter() {
    }

    static double factor(List<ActiveDirective> ds, int zone, double x, double y, long nowMs) {
        double f = 1;
        for (ActiveDirective d : ds) {
            if (d.zone() != zone || d.untilMs() <= nowMs || Directive.AVOID_POINT.equals(d.kind())) continue;
            if (Math.hypot(d.x() - x, d.y() - y) > d.r()) continue;
            if (Directive.AVOID_SPOT.equals(d.kind())) return 0;
            if (Directive.PENALIZE_SPOT.equals(d.kind())) f *= 0.5;
        }
        return f;
    }

    /**
     * Кластеры охоты (поля cx, cy, score): запрещённые убираются, оштрафованные теряют половину оценки, список пересортировывается.
     * Нет правил — тот же список.
     */
    public static List<Map<String, Object>> apply(List<Map<String, Object>> clusters, List<ActiveDirective> ds, int zone, long nowMs) {
        if (ds.isEmpty()) return clusters;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : clusters) {
            double f = factor(ds, zone, ((Number) c.get("cx")).doubleValue(), ((Number) c.get("cy")).doubleValue(), nowMs);
            if (f == 0) continue;
            if (f != 1) c.put("score", ((Number) c.get("score")).doubleValue() * f);
            out.add(c);
        }
        out.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return out;
    }
}
