package com.example.wowmcp.game;

import com.example.wowmcp.config.WowProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Реальные Lua-источники из ресурсов должны проходить ограничения чата WoW. */
class LuaSourcesTest {

    private final LuaSources sources = new LuaSources(new WowProperties(null, null, null, null, null));

    @Test
    void everySetupLineFitsIntoOneChatLine() {
        List<String> lines = sources.setupLines();
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            assertThat(line).as(line).startsWith("/run").doesNotContain("|");
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).as(line).isLessThanOrEqualTo(255);
            assertThat(line.chars().allMatch(c -> c < 128)).as("ASCII: " + line).isTrue();
        }
    }

    @Test
    void containsDeathModuleAndVersionLine() {
        List<String> lines = sources.setupLines();
        assertThat(String.join("\n", lines)).contains("777007.5");          // таблица D
        assertThat(lines.get(lines.size() - 1)).isEqualTo("/run B[30]=" + sources.version());
    }
}
