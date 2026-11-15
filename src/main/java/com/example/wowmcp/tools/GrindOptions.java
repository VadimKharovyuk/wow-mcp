package com.example.wowmcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Проверка аргументов инструмента {@code grind} и сборка опций для {@code bot.start('grind', opts)}. */
final class GrindOptions {

    /** Значения по умолчанию для фильтра уровня цели в подборе точек. */
    static final int DEFAULT_MIN_LVL_DIFF = -5;
    static final int DEFAULT_MAX_LVL_DIFF = 2;

    private GrindOptions() {
    }

    /** Проверяет заданные (не null) аргументы и возвращает их как карту опций бота. */
    @SuppressWarnings("java:S107")
    static Map<String, Object> validated(
            Double radius, Integer maxKills, Integer maxMinutes, Integer minLvlDiff, Integer maxLvlDiff,
            Double manaBelow, String healKey, String drinkKey, Boolean loot, Integer respawnSec,
            Integer minFreeSlots, String mode, Double maxDistance, Boolean autoRevive) {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (radius != null) {
            requireRange(Double.isFinite(radius) && radius >= 0.5 && radius <= 10, "radius должен быть от 0.5 до 10");
            opts.put("radius", radius);
        }
        if (maxKills != null) {
            requireRange(maxKills >= 0 && maxKills <= 200, "maxKills должен быть от 0 до 200");
            opts.put("maxKills", maxKills);
        }
        if (maxMinutes != null) {
            requireRange(maxMinutes >= 0 && maxMinutes <= 1440, "maxMinutes должен быть от 0 (без лимита времени) до 1440");
            opts.put("maxMinutes", maxMinutes);
        }
        if (minLvlDiff != null) {
            requireRange(minLvlDiff >= -10 && minLvlDiff <= 5, "minLvlDiff должен быть от -10 до 5");
            opts.put("minLvlDiff", minLvlDiff);
        }
        if (maxLvlDiff != null) {
            requireRange(maxLvlDiff >= -10 && maxLvlDiff <= 5, "maxLvlDiff должен быть от -10 до 5");
            opts.put("maxLvlDiff", maxLvlDiff);
        }
        if (manaBelow != null) {
            requireRange(Double.isFinite(manaBelow) && manaBelow >= 0 && manaBelow <= 1, "manaBelow должен быть от 0 до 1");
            opts.put("manaBelow", manaBelow);
        }
        if (healKey != null) {
            requireRange(healKey.matches("[0-9]"), "healKey должен быть слотом '0'..'9'");
            opts.put("healKey", healKey);
        }
        if (drinkKey != null) {
            requireRange(drinkKey.matches("[0-9]"), "drinkKey должен быть слотом '0'..'9'");
            opts.put("drinkKey", drinkKey);
        }
        if (respawnSec != null) {
            requireRange(respawnSec >= 60 && respawnSec <= 1800, "respawnSec должен быть от 60 до 1800");
            opts.put("respawnSec", respawnSec);
        }
        if (loot != null) {
            opts.put("loot", loot);
        }
        if (minFreeSlots != null) {
            requireRange(minFreeSlots >= 0 && minFreeSlots <= 10, "minFreeSlots должен быть от 0 до 10");
            opts.put("minFreeSlots", minFreeSlots);
        }
        if (mode != null) {
            String modeNorm = mode.trim().toLowerCase(Locale.ROOT);
            requireRange(modeNorm.equals("radius") || modeNorm.equals("patrol"), "mode должен быть 'radius' или 'patrol'");
            opts.put("mode", modeNorm);
        }
        if (maxDistance != null) {
            requireRange(Double.isFinite(maxDistance) && maxDistance >= 2 && maxDistance <= 40, "maxDistance должен быть от 2 до 40");
            opts.put("maxDistance", maxDistance);
        }
        if (autoRevive != null) {
            opts.put("autoRevive", autoRevive);
        }
        return opts;
    }

    /**
     * Playwright принимает в page.evaluate только Integer/Double/String/Boolean/Map/List:
     * Long (count) и BigDecimal (round(avg(...))) из БД валидны для JDBC, но не для Playwright.
     * Приводим каждое место к { x: double, y: double, kills: int, lvl: int|null }.
     */
    static List<Map<String, Object>> cleanSpots(List<Map<String, Object>> spots) {
        List<Map<String, Object>> cleanSpots = new java.util.ArrayList<>();
        for (Map<String, Object> p : spots) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("x", ((Number) p.get("x")).doubleValue());
            s.put("y", ((Number) p.get("y")).doubleValue());
            s.put("kills", ((Number) p.get("kills")).intValue());
            Object lvl = p.get("lvl");
            s.put("lvl", lvl == null ? null : ((Number) lvl).intValue());
            cleanSpots.add(s);
        }
        return cleanSpots;
    }

    private static void requireRange(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
