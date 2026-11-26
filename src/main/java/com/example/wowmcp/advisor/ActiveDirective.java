package com.example.wowmcp.advisor;

/** Действующее правило советника из таблицы advisor_directive: срок в миллисекундах эпохи. */
public record ActiveDirective(long id, String kind, int zone, double x, double y, double r, long untilMs, String reason) {
}
