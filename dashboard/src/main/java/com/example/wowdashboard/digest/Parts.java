package com.example.wowdashboard.digest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Разбивка периода на три равные части (от старой к новой): подписи времени и нули для частей без событий. */
final class Parts {

    static final int COUNT = 3;

    /** Ключи со счётчиками частей — те же, что возвращает {@link DigestRepository#partRows}. */
    private static final List<String> KEYS = List.of("fights", "fights_bad", "avg_fight_sec", "attacker_found", "attacker_missed",
            "turn_found", "turn_missed", "mount_ok", "mount_fail", "approach_fail", "approach_ok", "deaths", "warn_error",
            "press_not_facing", "press_too_far", "press_no_reaction", "press_other");

    private Parts() {
    }

    /**
     * @param rows    строки из БД (колонка part, счётчики); могут отсутствовать для частей без событий
     * @param minutes длина всего периода
     * @param nowText «сейчас» по БД в формате yyyy-MM-dd HH:mm:ss — от него считаются подписи частей
     */
    static List<Map<String, Object>> build(List<Map<String, Object>> rows, int minutes, String nowText) {
        LocalDateTime now = LocalDateTime.parse(nowText, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int p = 0; p < COUNT; p++) {
            final int part = p;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", hm.format(now.minusSeconds(Math.round(minutes * 60.0 * (COUNT - p) / COUNT))));
            row.put("to", hm.format(now.minusSeconds(Math.round(minutes * 60.0 * (COUNT - p - 1) / COUNT))));
            Map<String, Object> found = rows.stream().filter(r -> ((Number) r.get("part")).intValue() == part).findFirst().orElse(Map.of());
            for (String k : KEYS) {
                Object v = found.get(k);
                row.put(k, v == null ? 0 : v);
            }
            out.add(row);
        }
        return out;
    }
}
