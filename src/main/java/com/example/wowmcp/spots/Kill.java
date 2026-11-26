package com.example.wowmcp.spots;

/** Убийство моба (mob_kill): время (секунды), зона бота, координаты 0..100, уровни моба и игрока (могут быть неизвестны), кто убил. */
public record Kill(long ts, int zone, double x, double y, Integer mobLevel, Integer playerLevel, boolean bot) {
}
