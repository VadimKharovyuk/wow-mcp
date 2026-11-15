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
}
