package com.example.wowmcp.advisor;

import com.example.wowmcp.spots.SpotStat;

import java.util.ArrayList;
import java.util.List;

/**
 * Правила советника без БД и без модели: из сигналов получаются правила для бота с границами и сроком.
 * Пороги подобраны осторожно: правило появляется только при повторении, а не из-за единичного случая.
 */
public final class AdvisorRules {

    /** Точка упора считается постоянной, если упирались не меньше стольких раз. */
    static final int MIN_STUCK = 3;
    /** Смертей в клетке за 2 часа, чтобы запретить её на час. */
    static final long MIN_DEATHS = 2;
    /** Зависших боёв («бой без противника», «бой слишком долгий») в клетке за 30 минут, чтобы запретить её на 30 минут. */
    static final long MIN_STALLED = 2;
    /** Клетка хуже среднего на столько процентов (при надёжности не ниже средней) — понизить приоритет. */
    static final long BAD_VS_AVERAGE = -40;

    private AdvisorRules() {
    }

    public static List<Directive> evaluate(Signals s) {
        List<Directive> out = new ArrayList<>();
        for (StuckPoint p : s.stuckPoints()) {
            if (p.n() >= MIN_STUCK) {
                out.add(new Directive(Directive.AVOID_POINT, p.zone(), p.x(), p.y(), 0.15, 24 * 60, "упирались " + p.n() + " раз"));
            }
        }
        for (CellCount c : s.deaths()) {
            if (c.n() >= MIN_DEATHS) {
                out.add(new Directive(Directive.AVOID_SPOT, c.zone(), c.cx(), c.cy(), 1.4, 60, c.n() + " смертей за 2 часа"));
            }
        }
        for (CellCount c : s.stalledFights()) {
            if (c.n() >= MIN_STALLED) {
                out.add(new Directive(Directive.AVOID_SPOT, c.zone(), c.cx(), c.cy(), 1.4, 30, c.n() + " зависших боёв за 30 минут"));
            }
        }
        for (SpotStat sp : s.spots()) {
            if (!"низкая".equals(sp.confidence()) && sp.vsAveragePct() <= BAD_VS_AVERAGE) {
                out.add(new Directive(Directive.PENALIZE_SPOT, sp.zone(), sp.cx(), sp.cy(), 1.4, 120,
                        "опыт в час на " + Math.abs(sp.vsAveragePct()) + "% ниже среднего (" + sp.xpPerHour() + ")"));
            }
        }
        return out;
    }
}
