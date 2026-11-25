package com.example.wowdashboard.digest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlagsTest {

    /** Часть периода: бои, найден/не найден атакующий, доворот нашёл/не нашёл, конь сел/не сел, подход ок/провал, смерти, средний бой. */
    private static Map<String, Object> part(int fights, double avgSec, int af, int am, int tf, int tm, int mo, int mf, int ao, int afl, int deaths) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("fights", fights); m.put("avg_fight_sec", avgSec);
        m.put("attacker_found", af); m.put("attacker_missed", am); m.put("turn_found", tf); m.put("turn_missed", tm);
        m.put("mount_ok", mo); m.put("mount_fail", mf); m.put("approach_ok", ao); m.put("approach_fail", afl); m.put("deaths", deaths);
        return m;
    }

    @Test
    void improvementIsReportedAsGrowthNotDrop() {
        // атакующий: 0 из 13 → 19 из 27; конь: 0 из 6 → 13 из 13
        List<String> f = Flags.compute(List.of(
                part(9, 44, 0, 13, 1, 7, 0, 6, 8, 3, 0), part(18, 48, 27, 3, 0, 6, 11, 0, 15, 2, 0), part(21, 45, 19, 8, 0, 0, 13, 0, 16, 1, 0)));
        assertThat(f).anyMatch(s -> s.startsWith("Улучшение: поиск атакующего") && s.contains("выросло с 0% до 70%"));
        assertThat(f).anyMatch(s -> s.startsWith("Улучшение: посадка на коня") && s.contains("с 0% до 100%"));
        assertThat(f).noneMatch(s -> s.contains("упало"));
    }

    @Test
    void lowEfficiencyOverWholePeriod() {
        // доворот: нашёл 2 из 22 — 9%
        List<String> f = Flags.compute(List.of(
                part(10, 40, 10, 0, 1, 9, 5, 0, 9, 1, 0), part(10, 40, 10, 0, 1, 9, 5, 0, 9, 1, 0), part(10, 40, 10, 0, 0, 2, 5, 0, 9, 1, 0)));
        assertThat(f).anyMatch(s -> s.startsWith("Низкая эффективность: доворот") && s.contains("удалось 9% (2 из 22)"));
    }

    @Test
    void worseningDeathsAndFightDuration() {
        List<String> f = Flags.compute(List.of(
                part(10, 30, 10, 0, 0, 0, 5, 0, 10, 0, 0), part(10, 40, 5, 5, 0, 0, 5, 0, 5, 5, 1), part(10, 60, 2, 8, 0, 0, 5, 0, 3, 7, 2)));
        assertThat(f).anyMatch(s -> s.startsWith("Ухудшение: поиск атакующего") && s.contains("с 100% до 20%"));
        assertThat(f).anyMatch(s -> s.startsWith("Ухудшение: подход к цели"));
        assertThat(f).anyMatch(s -> s.equals("Смерти: 3 за период."));
        assertThat(f).anyMatch(s -> s.startsWith("Ухудшение: средняя длительность боя выросла с 30.0 до 60.0"));
    }

    @Test
    void smallSamplesAndStableMetricsGiveNoFlags() {
        List<String> f = Flags.compute(List.of(
                part(3, 40, 2, 1, 0, 1, 1, 0, 3, 0, 0), part(3, 41, 3, 0, 0, 0, 1, 0, 3, 0, 0), part(3, 42, 2, 1, 0, 0, 1, 0, 3, 0, 0)));
        assertThat(f).isEmpty();
    }

    @Test
    void middlePartDipIsReported() {
        // атакующий: 85% → 63% (10 из 16) → 100%; бои 28 с → 56 с → 29 с
        List<String> f = Flags.compute(List.of(
                part(15, 27.7, 11, 2, 0, 0, 9, 0, 12, 1, 0), part(9, 56.5, 10, 6, 0, 0, 3, 0, 8, 0, 0), part(18, 28.8, 8, 0, 1, 0, 5, 0, 14, 0, 0)));
        assertThat(f).anyMatch(s -> s.startsWith("Провал в середине периода: поиск атакующего") && s.contains("63% против 85% и 100%"));
        assertThat(f).anyMatch(s -> s.startsWith("Всплеск в середине периода: средняя длительность боя 56.5"));
    }
}
