package com.example.wowmcp.learn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearnPolicyTest {

    private final LearnPolicy policy = new LearnPolicy(List.of("hunter"));

    @Test
    void nameHashMatchesKnownSpells() {
        // значения из игры (nameHash заклинания в spells() / spell_cast)
        assertThat(LearnPolicy.nameHash("Автоматическая стрельба")).isEqualTo(1598442424);
        assertThat(LearnPolicy.nameHash("Лечение питомца")).isEqualTo(1635767960);
    }

    @Test
    void excludesAutoShotAttackAndPetSpells() {
        assertThat(policy.isExcluded(1598442424)).isTrue();                       // Автоматическая стрельба
        assertThat(policy.isExcluded(LearnPolicy.nameHash("Атака"))).isTrue();
        assertThat(policy.isExcluded(LearnPolicy.nameHash("Кормление питомца"))).isTrue();
        assertThat(policy.isExcluded(1635767960)).isTrue();                       // Лечение питомца
        assertThat(policy.isExcluded(1278643508)).isFalse();                      // Чародейский выстрел
        assertThat(policy.isExcluded(null)).isFalse();
    }

    @Test
    void autoLearnCanBeDisabledPerProfile() {
        assertThat(policy.isAutoLearnEnabled("paladin")).isTrue();
        assertThat(policy.isAutoLearnEnabled("hunter")).isFalse();
        assertThat(policy.isAutoLearnEnabled(null)).isFalse();
        assertThat(new LearnPolicy(List.of()).isAutoLearnEnabled("hunter")).isTrue();
    }

    @Test
    void manualRulesAreRecognized() {
        assertThat(LearnPolicy.isManualRule(Map.of("spell", 1, "maxPetHpPct", 0.5))).isTrue();
        assertThat(LearnPolicy.isManualRule(Map.of("spell", 1, "repeatMs", 15000))).isTrue();
        assertThat(LearnPolicy.isManualRule(Map.of("spell", 1, "maxHpPct", 0.35))).isTrue();
        assertThat(LearnPolicy.isManualRule(Map.of("key", "3"))).isTrue();
        // обычное правило и правило, выученное автоматически, не считаются ручными
        assertThat(LearnPolicy.isManualRule(Map.of("spell", 1))).isFalse();
        assertThat(LearnPolicy.isManualRule(Map.of("spell", 1, "maxHpPct", 0.4, "learned", true))).isFalse();
        assertThat(LearnPolicy.isManualRule(null)).isFalse();
    }
}
