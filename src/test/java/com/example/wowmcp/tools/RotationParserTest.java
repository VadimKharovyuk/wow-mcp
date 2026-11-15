package com.example.wowmcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RotationParserTest {

    @Test
    void acceptsValidRotation() {
        List<Map<String, Object>> rotation = RotationParser.parse(
                "[{\"key\":\"3\",\"maxHpPct\":0.4},{\"key\":\"2\"},{\"key\":1}]");

        assertThat(rotation).hasSize(3);
        assertThat(rotation.get(0)).containsEntry("key", "3").containsEntry("maxHpPct", 0.4);
        assertThat(rotation.get(1)).containsEntry("key", "2").doesNotContainKey("maxHpPct");
        assertThat(rotation.get(2)).containsEntry("key", "1");
    }

    @Test
    void acceptsOutOfCombatAndWaitAfter() {
        List<Map<String, Object>> rotation = RotationParser.parse(
                "[{\"key\":\"2\",\"maxHpPct\":0.35},"
                        + "{\"key\":\"4\",\"maxHpPct\":0.35,\"waitAfter\":{\"key\":\"2\",\"ms\":1000}},"
                        + "{\"key\":3,\"outOfCombatOnly\":true},"
                        + "{\"key\":\"1\"}]");

        assertThat(rotation).hasSize(4);
        assertThat(rotation.get(1)).containsEntry("key", "4");
        Map<?, ?> waitAfter = (Map<?, ?>) rotation.get(1).get("waitAfter");
        assertThat(waitAfter.get("key")).isEqualTo("2");
        assertThat(waitAfter.get("ms")).isEqualTo(1000);   // parseRotation кладёт Integer, не Long
        assertThat(rotation.get(2)).containsEntry("key", "3").containsEntry("outOfCombatOnly", true);
    }

    @Test
    void rejectsBadWaitAfter() {
        assertThatThrownBy(() -> RotationParser.parse(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"0\",\"ms\":1000}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.key");
        assertThatThrownBy(() -> RotationParser.parse(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"2\",\"ms\":10001}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.ms");
        assertThatThrownBy(() -> RotationParser.parse(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"2\",\"ms\":\"soon\"}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.ms");
        assertThatThrownBy(() -> RotationParser.parse("[{\"key\":\"4\",\"waitAfter\":1000}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter");
    }

    @Test
    void rejectsNonBooleanOutOfCombatOnly() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"key\":\"3\",\"outOfCombatOnly\":\"yes\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outOfCombatOnly");
    }

    @Test
    void rejectsBadSlot() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"key\":\"0\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void rejectsMissingKey() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"maxHpPct\":0.5}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void rejectsThresholdOutOfRange() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"key\":\"1\",\"maxHpPct\":1.5}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHpPct");
    }

    @Test
    void rejectsDuplicateSlot() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"key\":\"1\"},{\"key\":\"1\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("больше одного раза");
    }

    @Test
    void rejectsEmptyOrBrokenJson() {
        assertThatThrownBy(() -> RotationParser.parse("[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RotationParser.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RotationParser.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPetHealAndRepeatRules() {
        List<Map<String, Object>> rotation = RotationParser.parse(
                "[{\"spell\":1635767960,\"maxPetHpPct\":0.5},{\"spell\":1554409780,\"repeatMs\":15000},{\"spell\":1278643508}]");

        assertThat(rotation.get(0)).containsEntry("spell", 1635767960).containsEntry("maxPetHpPct", 0.5);
        assertThat(rotation.get(1)).containsEntry("repeatMs", 15000);   // Integer, а не Long/Double
        assertThat(rotation.get(2)).doesNotContainKeys("maxPetHpPct", "repeatMs");
    }

    @Test
    void rejectsBadPetHpAndRepeat() {
        assertThatThrownBy(() -> RotationParser.parse("[{\"spell\":1,\"maxPetHpPct\":1.5}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPetHpPct");
        assertThatThrownBy(() -> RotationParser.parse("[{\"spell\":1,\"repeatMs\":-5}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repeatMs");
        assertThatThrownBy(() -> RotationParser.parse("[{\"spell\":1,\"repeatMs\":1.5}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repeatMs");
    }
}
