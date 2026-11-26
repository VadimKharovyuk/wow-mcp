package com.example.wowmcp.spots;

import java.util.List;

/**
 * Результат расчёта: общая картина и клетки от лучшей к худшей.
 *
 * @param level           уровень игрока, по которому считали (окна на других уровнях не сравнимы и не учитываются)
 * @param windows         сколько 10-минутных окон попало в расчёт
 * @param hours           суммарное время этих окон
 * @param avgXpPerHour    средний опыт в час по всем учтённым окнам
 */
public record SpotResult(Integer level, int windows, double hours, long avgXpPerHour, List<SpotStat> spots) {
}
