package com.example.wowmcp.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HuntRepositoryTest {

    @Test
    void scorePrefersMoreSpawnsCloserAndMoreSuccessfulClusters() {
        double base = HuntRepository.score(20, 1.0, 10);                 // 20 * 1 / 2
        assertThat(base).isEqualTo(10.0);
        assertThat(HuntRepository.score(40, 1.0, 10)).isGreaterThan(base);
        assertThat(HuntRepository.score(20, 1.0, 0)).isGreaterThan(base);
        assertThat(HuntRepository.score(20, 0.25, 10)).isLessThan(base); // раньше часто пусто
    }

    private static com.example.wowmcp.spots.SpotStat stat(long vsAveragePct, String confidence) {
        return new com.example.wowmcp.spots.SpotStat(401, 81, 29, 60, 0.5, 236000, 130.0, 0, 0, 236000, vsAveragePct, confidence);
    }

    @Test
    void measuredXpPerHourAdjustsClusterScore() {
        double base = 100.0;
        assertThat(HuntRepository.adjusted(base, null)).isEqualTo(base);                          // измерений нет — прежняя оценка
        assertThat(HuntRepository.adjusted(base, stat(80, "низкая"))).isEqualTo(base);            // ненадёжное измерение не учитываем
        assertThat(HuntRepository.adjusted(base, stat(30, "средняя"))).isEqualTo(130.0);
        assertThat(HuntRepository.adjusted(base, stat(-20, "высокая"))).isEqualTo(80.0);
        assertThat(HuntRepository.adjusted(base, stat(300, "высокая"))).isEqualTo(150.0);          // потолок +50%
        assertThat(HuntRepository.adjusted(base, stat(-89, "высокая"))).isEqualTo(50.0);           // пол −50%
    }
}
