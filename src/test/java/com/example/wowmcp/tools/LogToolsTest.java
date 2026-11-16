package com.example.wowmcp.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogToolsTest {

    @Test
    void parseDefaults() {
        LogTools.Query q = LogTools.parse(null);
        assertThat(q.minutes()).isEqualTo(LogTools.DEFAULT_MINUTES);
        assertThat(q.profile()).isNull();
        assertThat(q.category()).isNull();
    }

    @Test
    void parseHoursCategoryProfile() {
        LogTools.Query q = LogTools.parse("2 часа лут паладин");
        assertThat(q.minutes()).isEqualTo(120);
        assertThat(q.category()).isEqualTo("loot");
        assertThat(q.profile()).isEqualTo("paladin");
    }

    @Test
    void parseMinutesAllProfilesAndLimit() {
        assertThat(LogTools.parse("30 минут все").minutes()).isEqualTo(30);
        assertThat(LogTools.parse("30 минут все").profile()).isEqualTo("*");
        assertThat(LogTools.parse("999 дней").minutes()).isEqualTo(10 * 1440);   // не больше срока хранения
    }
}
