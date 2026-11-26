package com.example.wowdashboard.hunting;

/** Убийство моба (mob_kill): время (секунды), зона бота, координаты 0..100, уровни моба и игрока (могут быть неизвестны), кто убил. */
record Kill(long ts, int zone, double x, double y, Integer mobLevel, Integer playerLevel, boolean bot) {
}
