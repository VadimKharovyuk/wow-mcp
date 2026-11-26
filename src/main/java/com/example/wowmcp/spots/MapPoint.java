package com.example.wowmcp.spots;

/** Точка события на карте (смерть, упор): зона бота и координаты 0..100. */
public record MapPoint(int zone, double x, double y) {
}
