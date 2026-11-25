package com.example.wowdashboard.common;

/** Разбор параметров периода. */
public final class Periods {

    public static final int DEFAULT_HOURS = 24;
    public static final int MAX_HOURS = 24 * 90;

    private Periods() {
    }

    /** Часы назад от сейчас: null → 24, ограничение 1..2160. */
    public static int hours(Integer hours) {
        if (hours == null) return DEFAULT_HOURS;
        return Math.max(1, Math.min(MAX_HOURS, hours));
    }

    public static final int DEFAULT_MINUTES = 60;
    public static final int MAX_MINUTES = 7 * 24 * 60;

    /** Минуты назад от сейчас для сводки журнала: null → 60, ограничение 5..10080. */
    public static int minutes(Integer minutes) {
        if (minutes == null) return DEFAULT_MINUTES;
        return Math.max(5, Math.min(MAX_MINUTES, minutes));
    }

    /** Шаг графиков: час для суток и меньше, иначе сутки. Только значения из белого списка (идут в SQL как есть). */
    public static String bucket(int hours) {
        return hours <= 48 ? "hour" : "day";
    }
}
