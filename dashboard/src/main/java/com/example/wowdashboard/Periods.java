package com.example.wowdashboard;

/** Разбор параметров периода. */
final class Periods {

    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 24 * 90;

    private Periods() {
    }

    /** Часы назад от сейчас: null → 24, ограничение 1..2160. */
    static int hours(Integer hours) {
        if (hours == null) return DEFAULT_HOURS;
        return Math.max(1, Math.min(MAX_HOURS, hours));
    }

    /** Шаг графиков: час для суток и меньше, иначе сутки. Только значения из белого списка (идут в SQL как есть). */
    static String bucket(int hours) {
        return hours <= 48 ? "hour" : "day";
    }
}
