package com.example.wowmcp.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameBridgeTest {

    @Test
    void convertsTimeoutsToIntForPlaywright() {
        // Playwright умеет передавать в страницу только Integer/Double, не Long.
        assertThat(GameBridge.toPageTimeoutMs(0)).isZero();
        assertThat(GameBridge.toPageTimeoutMs(3_562)).isEqualTo(3_562);
        assertThat(GameBridge.toPageTimeoutMs(-5)).isZero();
        assertThat(GameBridge.toPageTimeoutMs(5_000_000_000L)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void luaChunksStripLeadingCommentAndKeepWholeCode() {
        // Файл с комментарием в первой строке после обработки должен содержать весь код без комментария.
        List<String> chunks = GameBridge.splitLuaChunks("-- leading comment\nlocal x = 1\n");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("local x = 1");
    }

    @Test
    void luaChunksRejectNonAsciiAfterCommentRemoval() {
        assertThatThrownBy(() -> GameBridge.splitLuaChunks("local s = \"Привет\""))
                .isInstanceOf(IllegalStateException.class);
    }
}
