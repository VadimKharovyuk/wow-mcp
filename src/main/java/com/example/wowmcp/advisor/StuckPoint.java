package com.example.wowmcp.advisor;

/** Постоянная точка упора: зона, координаты (сетка 0,1), сколько раз упирались. */
public record StuckPoint(int zone, double x, double y, int n) {
}
