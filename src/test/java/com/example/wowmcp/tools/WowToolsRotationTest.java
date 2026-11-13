package com.example.wowmcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WowToolsRotationTest {

    @Test
    void acceptsValidRotation() {
        List<Map<String, Object>> rotation = WowTools.parseRotation(
                "[{\"key\":\"3\",\"maxHpPct\":0.4},{\"key\":\"2\"},{\"key\":1}]");

        assertThat(rotation).hasSize(3);
        assertThat(rotation.get(0)).containsEntry("key", "3").containsEntry("maxHpPct", 0.4);
        assertThat(rotation.get(1)).containsEntry("key", "2").doesNotContainKey("maxHpPct");
        assertThat(rotation.get(2)).containsEntry("key", "1");
    }

    @Test
    void acceptsOutOfCombatAndWaitAfter() {
        List<Map<String, Object>> rotation = WowTools.parseRotation(
                "[{\"key\":\"2\",\"maxHpPct\":0.35},"
                        + "{\"key\":\"4\",\"maxHpPct\":0.35,\"waitAfter\":{\"key\":\"2\",\"ms\":1000}},"
                        + "{\"key\":3,\"outOfCombatOnly\":true},"
                        + "{\"key\":\"1\"}]");

        assertThat(rotation).hasSize(4);
        assertThat(rotation.get(1)).containsEntry("key", "4");
        Map<?, ?> waitAfter = (Map<?, ?>) rotation.get(1).get("waitAfter");
        assertThat(waitAfter.get("key")).isEqualTo("2");
        assertThat(waitAfter.get("ms")).isEqualTo(1000L);
        assertThat(rotation.get(2)).containsEntry("key", "3").containsEntry("outOfCombatOnly", true);
    }

    @Test
    void rejectsBadWaitAfter() {
        assertThatThrownBy(() -> WowTools.parseRotation(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"0\",\"ms\":1000}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.key");
        assertThatThrownBy(() -> WowTools.parseRotation(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"2\",\"ms\":10001}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.ms");
        assertThatThrownBy(() -> WowTools.parseRotation(
                "[{\"key\":\"4\",\"waitAfter\":{\"key\":\"2\",\"ms\":\"soon\"}}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter.ms");
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"key\":\"4\",\"waitAfter\":1000}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitAfter");
    }

    @Test
    void rejectsNonBooleanOutOfCombatOnly() {
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"key\":\"3\",\"outOfCombatOnly\":\"yes\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outOfCombatOnly");
    }

    @Test
    void rejectsBadSlot() {
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"key\":\"0\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void rejectsMissingKey() {
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"maxHpPct\":0.5}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void rejectsThresholdOutOfRange() {
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"key\":\"1\",\"maxHpPct\":1.5}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHpPct");
    }

    @Test
    void rejectsDuplicateSlot() {
        assertThatThrownBy(() -> WowTools.parseRotation("[{\"key\":\"1\"},{\"key\":\"1\"}]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("больше одного раза");
    }

    @Test
    void rejectsEmptyOrBrokenJson() {
        assertThatThrownBy(() -> WowTools.parseRotation("[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WowTools.parseRotation("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WowTools.parseRotation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
