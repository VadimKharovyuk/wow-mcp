package com.example.wowmcp.advisor;

/**
 * Правило советника для бота (только из белого списка видов, с границами и сроком действия).
 *
 * @param kind       avoid_point — постоянная точка упора (обходить); avoid_spot — клетка карты, где охотиться нельзя (смерти, зависшие бои);
 *                   penalize_spot — клетка заметно хуже среднего по опыту в час (понизить приоритет)
 * @param zone       зона бота (код)
 * @param x, y       центр (единицы карты 0..100)
 * @param r          радиус (единицы карты)
 * @param ttlMinutes сколько минут правило действует
 * @param reason     почему (для журнала и дашборда)
 */
public record Directive(String kind, int zone, double x, double y, double r, int ttlMinutes, String reason) {

    public static final String AVOID_POINT = "avoid_point";
    public static final String AVOID_SPOT = "avoid_spot";
    public static final String PENALIZE_SPOT = "penalize_spot";
}
