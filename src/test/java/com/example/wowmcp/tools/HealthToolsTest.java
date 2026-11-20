package com.example.wowmcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthToolsTest {

    private static HealthTools.Snapshot ok() {
        return new HealthTools.Snapshot(true, true, true, false, "abc", "abc", "809258", "809258.0", 3, 10);
    }

    @Test
    void healthyHasNoProblems() {
        assertEquals(List.of(), HealthTools.problems(ok()));
    }

    @Test
    void dbDisabledIsReported() {
        var s = new HealthTools.Snapshot(true, false, true, false, "abc", "abc", "1", "1", 3, 3);
        assertTrue(HealthTools.problems(s).get(0).contains("База выключена"));
    }

    @Test
    void staleJsAndLuaAreReported() {
        var s = new HealthTools.Snapshot(true, true, true, false, "new", "old", "2", "1", 3, 3);
        String all = String.join("\n", HealthTools.problems(s));
        assertTrue(all.contains("JS в игре (old)"));
        assertTrue(all.contains("Lua в игре (1)"));
    }

    @Test
    void botNotLoadedSkipsVersionChecks() {
        var s = new HealthTools.Snapshot(true, true, false, false, "new", null, "2", null, 3, 3);
        List<String> p = HealthTools.problems(s);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("не загружен"));
    }

    @Test
    void oldLoaderWithoutVersionIsReported() {
        var s = new HealthTools.Snapshot(true, true, true, false, "abc", null, "1", "1", 3, 3);
        assertTrue(HealthTools.problems(s).get(0).contains("старый загрузчик"));
    }

    @Test
    void stalledCollectorIsReportedOnlyForLeader() {
        var leader = new HealthTools.Snapshot(true, true, true, false, "a", "a", "1", "1", 500, 500);
        var other = new HealthTools.Snapshot(false, true, true, false, "a", "a", "1", "1", 500, 500);
        assertTrue(String.join("\n", HealthTools.problems(leader)).contains("Сбор событий"));
        assertEquals(List.of(), HealthTools.problems(other));
    }

    @Test
    void luaDataNotReadable() {
        var s = new HealthTools.Snapshot(true, true, true, false, "a", "a", "1", "1", 3, 3, false);
        assertTrue(HealthTools.problems(s).stream().anyMatch(x -> x.startsWith("❗") && x.contains("Данные из игры неполные")));
        var ok = new HealthTools.Snapshot(true, true, true, false, "a", "a", "1", "1", 3, 3, true);
        assertTrue(HealthTools.problems(ok).isEmpty());
    }
}
