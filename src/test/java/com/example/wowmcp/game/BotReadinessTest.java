package com.example.wowmcp.game;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotReadinessTest {

    @Test
    void convertsTimeoutsToIntForPlaywright() {
        // Playwright умеет передавать в страницу только Integer/Double, не Long.
        assertThat(BotReadiness.toPageTimeoutMs(0)).isZero();
        assertThat(BotReadiness.toPageTimeoutMs(3_562)).isEqualTo(3_562);
        assertThat(BotReadiness.toPageTimeoutMs(-5)).isZero();
        assertThat(BotReadiness.toPageTimeoutMs(5_000_000_000L)).isEqualTo(Integer.MAX_VALUE);
    }
}
