package com.example.wowmcp.advisor;

/** Сколько событий случилось в клетке карты 2×2 (зона бота, центр клетки). */
public record CellCount(int zone, int cx, int cy, long n) {
}
