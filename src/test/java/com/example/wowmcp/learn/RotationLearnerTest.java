package com.example.wowmcp.learn;

import com.example.wowmcp.db.CastHistoryRepository;
import com.example.wowmcp.db.Database;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RotationLearnerTest {

    private static final int AUTO_SHOT = 1598442424;
    private static final int PET_HEAL = 1635767960;
    private static final int ARCANE = 1278643508;
    private static final int SERPENT = 1554409780;

    private static Map<String, Object> cast(int hash, boolean fight, Integer prev, Integer prevMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name_hash", hash);
        r.put("fight", fight);
        r.put("hp", 0.9);
        r.put("prev_spell", prev);
        r.put("prev_spell_ms", prevMs);
        r.put("ready_spells", List.of(ARCANE, SERPENT));
        return r;
    }

    private RotationLearner learner(List<Map<String, Object>> rows) {
        Database db = mock(Database.class);
        when(db.isEnabled()).thenReturn(true);
        CastHistoryRepository casts = mock(CastHistoryRepository.class);
        when(casts.querySpellCasts("hunter")).thenReturn(rows);
        return new RotationLearner(db, casts, new LearnPolicy(List.of()));
    }

    private List<Map<String, Object>> history() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(cast(ARCANE, true, AUTO_SHOT, 400));    // после автострельбы
            rows.add(cast(SERPENT, true, null, null));
            rows.add(cast(AUTO_SHOT, true, null, null));
            rows.add(cast(PET_HEAL, true, null, null));
        }
        return rows;
    }

    @Test
    void doesNotLearnExcludedSpellsOrWaitAfterThem() {
        RotationLearner.SpellLearn res = learner(history()).learnSpells("hunter", List.of());

        assertThat(res.result()).isNotNull();
        List<Object> spells = res.result().rotation().stream().map(r -> r.get("spell")).toList();
        assertThat(spells).containsExactlyInAnyOrder(ARCANE, SERPENT);
        assertThat(spells).doesNotContain(AUTO_SHOT, PET_HEAL);
        assertThat(res.result().rotation()).allSatisfy(r -> {
            assertThat(r).doesNotContainKey("waitAfter");     // автострельба не годится как «после чего»
            assertThat(r).containsEntry("learned", true);
        });
    }

    @Test
    void keepsManualRulesAtTheFrontAndDoesNotRelearnThem() {
        List<Map<String, Object>> current = List.of(
                Map.of("spell", PET_HEAL, "maxPetHpPct", 0.5),
                Map.of("spell", SERPENT, "repeatMs", 15000),
                Map.of("key", "9"),
                Map.of("spell", 42, "maxHpPct", 0.4, "learned", true));   // выученное — пересчитывается

        RotationLearner.SpellLearn res = learner(history()).learnSpells("hunter", current);

        List<Map<String, Object>> rotation = res.result().rotation();
        assertThat(rotation.get(0)).containsEntry("spell", PET_HEAL).containsEntry("maxPetHpPct", 0.5);
        assertThat(rotation.get(1)).containsEntry("spell", SERPENT).containsEntry("repeatMs", 15000);
        assertThat(rotation.get(2)).containsEntry("key", "9");
        assertThat(rotation.stream().filter(r -> Integer.valueOf(SERPENT).equals(r.get("spell")))).hasSize(1);
        assertThat(rotation.stream().map(r -> r.get("spell"))).doesNotContain(42);
        assertThat(rotation.stream().map(r -> r.get("spell"))).contains(ARCANE);
    }

    @Test
    void onlyExcludedSpellsMeansNothingToLearnAndRotationIsKept() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(cast(AUTO_SHOT, true, null, null));

        RotationLearner.SpellLearn res = learner(rows).learnSpells("hunter", List.of());

        assertThat(res.result()).isNull();
        assertThat(res.isInsufficient()).isTrue();
    }

    @Test
    void lastLearnedCountCountsAllRows() {
        RotationLearner learner = learner(history());
        learner.learnSpells("hunter", List.of());
        // иначе EventCollector сравнивал бы с отфильтрованным числом и переучивал на каждом тике
        assertThat(learner.lastLearnedCount("hunter")).isEqualTo(history().size());
    }
}
