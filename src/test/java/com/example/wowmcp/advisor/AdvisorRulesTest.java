package com.example.wowmcp.advisor;

import com.example.wowmcp.spots.SpotStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorRulesTest {

    private static SpotStat spot(int cx, int cy, long vs, String confidence) {
        return new SpotStat(401, cx, cy, 60, 0.5, 100000, 120.0, 0, 0, 100000, vs, confidence);
    }

    private static Signals signals(List<CellCount> deaths, List<CellCount> stalled, List<StuckPoint> stuck, List<SpotStat> spots) {
        return new Signals(deaths, stalled, stuck, spots);
    }

    @Test
    void noSignalsNoDirectives() {
        assertThat(AdvisorRules.evaluate(signals(List.of(), List.of(), List.of(), List.of()))).isEmpty();
    }

    @Test
    void persistentStuckPointNeedsThreeTimes() {
        List<Directive> d = AdvisorRules.evaluate(signals(List.of(), List.of(),
                List.of(new StuckPoint(401, 79.3, 26.5, 2), new StuckPoint(401, 80.1, 30.2, 3)), List.of()));
        assertThat(d).hasSize(1);
        assertThat(d.get(0).kind()).isEqualTo(Directive.AVOID_POINT);
        assertThat(d.get(0).x()).isEqualTo(80.1);
        assertThat(d.get(0).ttlMinutes()).isEqualTo(24 * 60);
        assertThat(d.get(0).reason()).contains("3 раз");
    }

    @Test
    void deathsAndStalledFightsBanCellForLimitedTime() {
        List<Directive> d = AdvisorRules.evaluate(signals(
                List.of(new CellCount(401, 79, 27, 1), new CellCount(401, 81, 29, 2)),
                List.of(new CellCount(401, 77, 25, 2), new CellCount(401, 75, 25, 1)), List.of(), List.of()));
        assertThat(d).hasSize(2);
        assertThat(d).allMatch(x -> Directive.AVOID_SPOT.equals(x.kind()));
        assertThat(d.get(0).ttlMinutes()).isEqualTo(60);                  // смерти: час
        assertThat(d.get(1).ttlMinutes()).isEqualTo(30);                  // зависшие бои: полчаса
        assertThat(d.get(0).r()).isEqualTo(1.4);
    }

    @Test
    void penalizeOnlyReliablyBadCells() {
        List<Directive> d = AdvisorRules.evaluate(signals(List.of(), List.of(), List.of(), List.of(
                spot(79, 27, -60, "средняя"), spot(81, 29, -60, "низкая"), spot(77, 25, -30, "высокая"), spot(79, 29, 20, "высокая"))));
        assertThat(d).hasSize(1);
        assertThat(d.get(0).kind()).isEqualTo(Directive.PENALIZE_SPOT);
        assertThat(d.get(0).x()).isEqualTo(79);
        assertThat(d.get(0).reason()).contains("60%");
    }

    @Test
    void advisorModeOnlyOffOrShow() {
        assertThat(AdvisorService.normalize("off")).isEqualTo("off");
        assertThat(AdvisorService.normalize(" OFF ")).isEqualTo("off");
        assertThat(AdvisorService.normalize("show")).isEqualTo("show");
        assertThat(AdvisorService.normalize("apply")).isEqualTo("apply");
        assertThat(AdvisorService.normalize("что-то")).isEqualTo("show");    // неизвестное — безопасный режим
        assertThat(AdvisorService.normalize(null)).isEqualTo("show");
    }
}
