package com.example.wowdashboard.digest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RatesTest {

    /** Map.of ограничен 10 парами, а ключей здесь 11: собираем по именам и числам подряд. */
    private static Map<String, Object> row(int... v) {
        String[] k = {"fights", "fights_bad", "attacker_found", "attacker_missed", "turn_found", "turn_missed",
                "mount_ok", "mount_fail", "approach_ok", "approach_fail", "deaths"};
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < k.length; i++) m.put(k[i], v[i]);
        return m;
    }

    @Test
    void computesPercentagesAndDeathsPerHour() {
        List<Map<String, Object>> parts = List.of(
                row(10, 1, 0, 10, 1, 9, 0, 4, 8, 2, 1),
                row(10, 0, 30, 0, 1, 9, 12, 0, 9, 1, 0));
        Map<String, Object> r = Rates.compute(parts, 120);
        assertThat(r.get("fightsOkPct")).isEqualTo(95.0);
        assertThat(r.get("attackerFoundPct")).isEqualTo(75.0);
        assertThat(r.get("turnFoundPct")).isEqualTo(10.0);
        assertThat(r.get("mountedPct")).isEqualTo(75.0);
        assertThat(r.get("approachOkPct")).isEqualTo(85.0);
        assertThat(r.get("deathsPerHour")).isEqualTo(0.5);
    }

    @Test
    void noDataGivesNull() {
        Map<String, Object> zero = row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        Map<String, Object> r = Rates.compute(List.of(zero), 60);
        assertThat(r.get("fightsOkPct")).isNull();
        assertThat(r.get("attackerFoundPct")).isNull();
        assertThat(r.get("mountedPct")).isNull();
        assertThat(r.get("deathsPerHour")).isEqualTo(0.0);
    }
}
