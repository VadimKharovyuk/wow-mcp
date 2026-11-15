package com.example.wowmcp.game;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuaMinifierTest {

    @Test
    void luaChunksStripLeadingCommentAndKeepWholeCode() {
        // Файл с комментарием в первой строке после обработки должен содержать весь код без комментария.
        List<String> chunks = LuaMinifier.splitLuaChunks("-- leading comment\nlocal x = 1\n");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("local x=1");
    }

    @Test
    void compressLuaKeepsStringsIntact() {
        String src = "local s = \" a = b , ( ) \"\n-- tail\nprint( s , 1 + 2 )";
        String compressed = LuaMinifier.compressLua(src);
        assertThat(compressed).contains("\" a = b , ( ) \"");
        assertThat(compressed).isEqualTo("local s=\" a = b , ( ) \" print(s,1+2)");
    }

    @Test
    void compressLuaKeepsSingleQuotedStringsIntact() {
        String src = "local s = ' a = b , ( ) '\nprint( s )";
        String compressed = LuaMinifier.compressLua(src);
        assertThat(compressed).contains("' a = b , ( ) '");
        assertThat(compressed).isEqualTo("local s=' a = b , ( ) ' print(s)");
    }

    @Test
    void compressLuaDoesNotTurnMinusMinusIntoComment() {
        String src = "local a = 5 - -1";
        String compressed = LuaMinifier.compressLua(src);
        assertThat(compressed).isEqualTo("local a=5- -1");
        assertThat(compressed).doesNotContain("--");
    }

    @Test
    void splitLuaChunksUsesCompressedSize() {
        String src = "local  a   =   1\nlocal b=2";
        List<String> chunks = LuaMinifier.splitLuaChunks(src);
        assertThat(String.join("", chunks)).isEqualTo("local a=1 local b=2");
    }

    @Test
    void luaChunksRejectNonAsciiAfterCommentRemoval() {
        assertThatThrownBy(() -> LuaMinifier.splitLuaChunks("local s = \"Привет\""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void luaChunksSplitByCompressedBytesLimit() {
        String src = "a=1;" + "b".repeat(300);
        String compressed = LuaMinifier.compressLua(src);
        assertThat(compressed.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(215);
        List<String> chunks = LuaMinifier.splitLuaChunks(src);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).length()).isLessThanOrEqualTo(215);
    }
}
