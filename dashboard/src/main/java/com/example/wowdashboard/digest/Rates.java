package com.example.wowdashboard.digest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowdashboard.digest.Nums.pct;
import static com.example.wowdashboard.digest.Nums.sum;

/** Готовые доли успеха за весь период по частям: проще оценить «85%», чем сравнивать строки. null — не на чем считать. */
final class Rates {

    private Rates() {
    }

    static Map<String, Object> compute(List<Map<String, Object>> parts, int minutes) {
        long fights = sum(parts, "fights"), bad = sum(parts, "fights_bad");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fightsOkPct", pct(fights - bad, fights));
        r.put("attackerFoundPct", pct(sum(parts, "attacker_found"), sum(parts, "attacker_found") + sum(parts, "attacker_missed")));
        r.put("turnFoundPct", pct(sum(parts, "turn_found"), sum(parts, "turn_found") + sum(parts, "turn_missed")));
        r.put("mountedPct", pct(sum(parts, "mount_ok"), sum(parts, "mount_ok") + sum(parts, "mount_fail")));
        r.put("approachOkPct", pct(sum(parts, "approach_ok"), sum(parts, "approach_ok") + sum(parts, "approach_fail")));
        r.put("deathsPerHour", minutes <= 0 ? null : Math.round(sum(parts, "deaths") * 600.0 / minutes) / 10.0);
        return r;
    }
}
