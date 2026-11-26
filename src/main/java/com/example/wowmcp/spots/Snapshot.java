package com.example.wowmcp.spots;

/** Снимок опыта игрока (game_event type=stat): время (секунды), уровень, опыт и опыт до следующего уровня. */
public record Snapshot(long ts, int level, long xp, long xpMax) {
}
