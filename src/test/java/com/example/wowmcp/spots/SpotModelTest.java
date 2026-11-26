package com.example.wowmcp.spots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotModelTest {

    /** Снимки опыта раз в 30 секунд на промежутке [fromSec, toSec), опыт растёт на xpPerSnap за снимок. */
    private static void snaps(List<Snapshot> out, long fromSec, long toSec, long startXp, long xpPerSnap, int level) {
        long xp = startXp;
        for (long t = fromSec; t < toSec; t += 30) {
            out.add(new Snapshot(t, level, xp, 1_000_000));
            xp += xpPerSnap;
        }
    }

    private static void kills(List<Kill> out, long fromSec, long toSec, long stepSec, int zone, double x, double y, boolean bot) {
        for (long t = fromSec; t < toSec; t += stepSec) out.add(new Kill(t, zone, x, y, 71, 71, bot));
    }

    @Test
    void cellIndexMatchesKillMap() {
        assertThat(SpotModel.cell(0.5)).isEqualTo(1);
        assertThat(SpotModel.cell(2.0)).isEqualTo(3);
        assertThat(SpotModel.cell(79.3)).isEqualTo(79);
        assertThat(SpotModel.cell(26.5)).isEqualTo(27);
    }

    @Test
    void richerCellGetsHigherXpPerHour() {
        // 0..1800 с: клетка A (опыт 100 за снимок = 12000 в час), 1800..3600 с: клетка B (опыт 200 за снимок = 24000 в час)
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 1800, 0, 100, 71);
        snaps(s, 1800, 3600, 6_000, 200, 71);                      // опыт продолжается без скачка
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 1800, 40, 401, 10, 10, true);
        kills(k, 1805, 3600, 40, 401, 50, 50, true);
        SpotResult r = SpotModel.compute(s, k, List.of(), List.of(), null);
        assertThat(r.level()).isEqualTo(71);
        assertThat(r.spots()).hasSize(2);
        SpotStat best = r.spots().get(0), worst = r.spots().get(1);
        assertThat(best.cx()).isEqualTo(51);
        assertThat(best.xpPerHour()).isBetween(22000L, 25000L);
        assertThat(worst.xpPerHour()).isBetween(11000L, 13000L);
        assertThat(best.vsAveragePct()).isPositive();
        assertThat(worst.vsAveragePct()).isNegative();
        assertThat(r.windows()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void windowsWithPlayerKillsAndGapsAreExcluded() {
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 1200, 0, 100, 71);
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 1200, 40, 401, 10, 10, true);
        kills(k, 700, 760, 20, 401, 10, 10, false);                // игрок убивал в окне 600..1200 — опыт общий, окно не считаем
        SpotResult r = SpotModel.compute(s, k, List.of(), List.of(), null);
        assertThat(r.windows()).isEqualTo(1);                      // осталось только первое окно 0..600
        // разрыв в снимках > 150 с — не работал, не считаем
        List<Snapshot> gap = new ArrayList<>();
        snaps(gap, 0, 300, 0, 100, 71);
        snaps(gap, 800, 1100, 50_000, 100, 71);
        assertThat(SpotModel.compute(gap, k, List.of(), List.of(), null).windows()).isLessThanOrEqualTo(1);
    }

    @Test
    void otherLevelWindowsAreSkippedAndLevelUpIsNotCounted() {
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 1200, 0, 100, 70);                             // старый уровень
        snaps(s, 1200, 2400, 0, 100, 71);                          // текущий
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 2400, 40, 401, 10, 10, true);
        SpotResult r = SpotModel.compute(s, k, List.of(), List.of(), null);
        assertThat(r.level()).isEqualTo(71);
        assertThat(r.hours()).isLessThanOrEqualTo(0.34);          // только 1200..2400 с (≈ 0,33 ч), окна уровня 70 не учтены
    }

    @Test
    void deathsAndStucksReduceScoreButNotXpPerHour() {
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 3600, 0, 100, 71);
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 3600, 30, 401, 10, 10, true);
        SpotResult clean = SpotModel.compute(s, k, List.of(), List.of(), null);
        SpotResult bad = SpotModel.compute(s, k, List.of(new MapPoint(401, 10, 10), new MapPoint(401, 11, 11)),
                List.of(new MapPoint(401, 10, 10)), null);
        SpotStat a = clean.spots().get(0), b = bad.spots().get(0);
        assertThat(b.xpPerHour()).isEqualTo(a.xpPerHour());
        assertThat(b.score()).isLessThan(a.score());
        assertThat(b.deaths()).isEqualTo(2);
        assertThat(b.stuck()).isEqualTo(1);
    }

    @Test
    void tooLittleDataGivesNoSpotsAndNoSnapshotsIsSafe() {
        assertThat(SpotModel.compute(List.of(), List.of(), List.of(), List.of(), null).spots()).isEmpty();
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 600, 0, 100, 71);
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 100, 40, 401, 10, 10, true);                   // 3 убийства < 5
        assertThat(SpotModel.compute(s, k, List.of(), List.of(), null).spots()).isEmpty();
    }

    @Test
    void confidenceDependsOnHours() {
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 7200, 0, 100, 71);
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 7200, 30, 401, 10, 10, true);
        assertThat(SpotModel.compute(s, k, List.of(), List.of(), null).spots().get(0).confidence()).isEqualTo("высокая");
    }

    @Test
    void killWeightGrowsWithMobLevel() {
        assertThat(SpotModel.weight(null, 71)).isEqualTo(1.0);
        assertThat(SpotModel.weight(73, 71)).isGreaterThan(SpotModel.weight(71, 71));
        assertThat(SpotModel.weight(60, 71)).isEqualTo(0.45, org.assertj.core.data.Offset.offset(0.001));
        assertThat(SpotModel.weight(30, 71)).isEqualTo(0.2);
    }

    @Test
    void singleDeathOnSmallSampleDoesNotWipeOutCell() {
        List<Snapshot> s = new ArrayList<>();
        snaps(s, 0, 1200, 0, 100, 71);                              // 20 минут ≈ 0,33 ч
        List<Kill> k = new ArrayList<>();
        kills(k, 5, 1200, 30, 401, 10, 10, true);
        SpotStat clean = SpotModel.compute(s, k, List.of(), List.of(), null).spots().get(0);
        SpotStat oneDeath = SpotModel.compute(s, k, List.of(new MapPoint(401, 10, 10)), List.of(), null).spots().get(0);
        assertThat(oneDeath.score()).isLessThan(clean.score());
        assertThat(oneDeath.score()).isGreaterThan(clean.score() / 2);   // штраф заметный, но клетка не обнулена
    }
}
