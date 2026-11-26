package com.example.wowmcp.advisor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DirectiveFilterTest {

    private static final long NOW = 1_000_000L;

    private static ActiveDirective d(String kind, int zone, double x, double y, double r, long untilMs) {
        return new ActiveDirective(1, kind, zone, x, y, r, untilMs, "тест");
    }

    private static Map<String, Object> cluster(double cx, double cy, double score) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("cx", cx);
        c.put("cy", cy);
        c.put("score", score);
        return c;
    }

    @Test
    void factorIsZeroInBannedHalfInPenalizedOneElsewhere() {
        List<ActiveDirective> ds = List.of(d(Directive.AVOID_SPOT, 401, 79, 27, 1.4, NOW + 1000),
                d(Directive.PENALIZE_SPOT, 401, 81, 29, 1.4, NOW + 1000), d(Directive.AVOID_POINT, 401, 60, 60, 0.15, NOW + 1000));
        assertThat(DirectiveFilter.factor(ds, 401, 79.5, 27.5, NOW)).isZero();
        assertThat(DirectiveFilter.factor(ds, 401, 81, 29, NOW)).isEqualTo(0.5);
        assertThat(DirectiveFilter.factor(ds, 401, 70, 70, NOW)).isEqualTo(1);
        assertThat(DirectiveFilter.factor(ds, 115, 79, 27, NOW)).isEqualTo(1);             // другая зона
        assertThat(DirectiveFilter.factor(ds, 401, 60, 60, NOW)).isEqualTo(1);             // точка упора места охоты не меняет
        assertThat(DirectiveFilter.factor(ds, 401, 79.5, 27.5, NOW + 2000)).isEqualTo(1);  // срок истёк
    }

    @Test
    void clustersAreFilteredPenalizedAndResorted() {
        List<ActiveDirective> ds = List.of(d(Directive.AVOID_SPOT, 401, 10, 10, 1.4, NOW + 1000),
                d(Directive.PENALIZE_SPOT, 401, 20, 20, 1.4, NOW + 1000));
        List<Map<String, Object>> in = new ArrayList<>(List.of(cluster(10, 10, 100), cluster(20, 20, 80), cluster(30, 30, 50)));
        List<Map<String, Object>> out = DirectiveFilter.apply(in, ds, 401, NOW);
        assertThat(out).hasSize(2);                                                        // запрещённый убран
        assertThat(out.get(0).get("cx")).isEqualTo(30.0);                                  // 50 > 80 * 0.5 = 40
        assertThat(((Number) out.get(1).get("score")).doubleValue()).isEqualTo(40.0);
    }

    @Test
    void noDirectivesLeaveListUntouched() {
        List<Map<String, Object>> in = new ArrayList<>(List.of(cluster(10, 10, 100)));
        assertThat(DirectiveFilter.apply(in, List.of(), 401, NOW)).isSameAs(in);
    }

    @Test
    void jsonForBotHasKindZoneCoordinatesRadiusAndUntil() {
        String json = AdvisorService.toJson(List.of(d(Directive.AVOID_SPOT, 401, 79, 27, 1.4, 1_700_000_000_000L)));
        assertThat(json).isEqualTo("[{\"kind\":\"avoid_spot\",\"zone\":401,\"x\":79.0,\"y\":27.0,\"r\":1.4,\"until\":1700000000000}]");
        assertThat(AdvisorService.toJson(List.of())).isEqualTo("[]");
    }
}
