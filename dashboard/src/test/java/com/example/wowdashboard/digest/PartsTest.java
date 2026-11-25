package com.example.wowdashboard.digest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartsTest {

    @Test
    void splitsPeriodIntoThreeLabeledPartsAndFillsMissingWithZeros() {
        // 60 минут, «сейчас» 02:00:00 → части 01:00–01:20, 01:20–01:40, 01:40–02:00; строка БД есть только для средней (part=1)
        List<Map<String, Object>> parts = Parts.build(
                List.of(Map.of("part", 1, "fights", 9L, "avg_fight_sec", 56.5, "attacker_found", 10L)), 60, "2026-11-26 02:00:00");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).containsEntry("from", "01:00").containsEntry("to", "01:20").containsEntry("fights", 0);
        assertThat(parts.get(1)).containsEntry("from", "01:20").containsEntry("to", "01:40").containsEntry("fights", 9L)
                .containsEntry("avg_fight_sec", 56.5).containsEntry("attacker_found", 10L).containsEntry("attacker_missed", 0);
        assertThat(parts.get(2)).containsEntry("from", "01:40").containsEntry("to", "02:00").containsEntry("deaths", 0);
    }

    @Test
    void everyPartHasAllCounterKeys() {
        List<Map<String, Object>> parts = Parts.build(List.of(), 30, "2026-11-26 12:30:00");
        for (Map<String, Object> p : parts) {
            assertThat(p).containsKeys("fights", "fights_bad", "avg_fight_sec", "attacker_found", "attacker_missed", "turn_found", "turn_missed",
                    "mount_ok", "mount_fail", "approach_ok", "approach_fail", "deaths", "warn_error",
                    "press_not_facing", "press_too_far", "press_no_reaction", "press_other");
        }
    }
}
