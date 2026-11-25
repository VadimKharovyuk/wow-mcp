package com.example.wowdashboard.digest;

import java.util.List;
import java.util.Map;

/** Мелкие числовые помощники для расчётов по сводке (значения приходят из БД как Number или null). */
final class Nums {

    private Nums() {
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** Сумма значения по ключу во всех частях периода. */
    static long sum(List<Map<String, Object>> parts, String key) {
        long s = 0;
        for (Map<String, Object> p : parts) if (p.get(key) instanceof Number n) s += n.longValue();
        return s;
    }

    /** Доля в процентах с одним знаком; null — делить не на что. */
    static Object pct(long a, long total) {
        return total <= 0 ? null : Math.round(a * 1000.0 / total) / 10.0;
    }
}
