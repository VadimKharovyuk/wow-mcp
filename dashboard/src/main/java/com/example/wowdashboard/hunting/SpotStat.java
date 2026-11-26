package com.example.wowdashboard.hunting;

/**
 * Итог по клетке карты 2×2 за период. Часы и опыт получены по окнам по 10 минут и разнесены по клеткам пропорционально убийствам бота.
 *
 * @param cx, cy         центр клетки (по тому же правилу, что карта убийств на странице: floor(x / 2) * 2 + 1)
 * @param hours          сколько часов работы бота приходится на клетку
 * @param xpPerHour      опыт в час по снимкам опыта
 * @param score          оценка = опыт в час со штрафом за смерти и упоры
 * @param vsAveragePct   оценка относительно среднего опыта в час по всем окнам, в процентах (+25 — на четверть лучше среднего)
 * @param confidence     надёжность: высокая (≥ 1 ч), средняя (≥ 0,4 ч), низкая
 */
public record SpotStat(int zone, int cx, int cy, long kills, double hours, long xpPerHour, double killsPerHour,
                       long deaths, long stuck, long score, long vsAveragePct, String confidence) {
}
