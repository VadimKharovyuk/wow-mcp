package com.example.wowdashboard.hunting;

/** Снимок опыта игрока (game_event type=stat): время (секунды), уровень, опыт и опыт до следующего уровня. */
record Snapshot(long ts, int level, long xp, long xpMax) {
}
