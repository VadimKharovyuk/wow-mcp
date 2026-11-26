package com.example.wowmcp.advisor;

import com.example.wowmcp.spots.SpotStat;

import java.util.List;

/** Всё, что видит советник за один проход: смерти и зависшие бои по клеткам, надёжные точки упора и измеренные клетки. */
public record Signals(List<CellCount> deaths, List<CellCount> stalledFights, List<StuckPoint> stuckPoints, List<SpotStat> spots) {
}
