package com.example.wowmcp.telegram;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    private static final String REPORT = """
            {"job":{"name":"grind","status":"running"},"hpPct":94,"x":44.6,"y":63.2,"zone":117,"pLvl":21,"fight":true,
             "manaPct":0.8,"petExists":true,"petHpPct":0.95,"petDead":false,"ammo":1517,"dead":false,"ghost":false,
             "grind":{"kills":25,"loots":11,"rests":2,"deaths":1,"lootResults":{"добыча собрана":11,"пустой":3},
                      "rejects":{"мёртв":4}},
             "hunt":null,"route":null,"warn":["лут: труп не найден"]}""";

    @Test
    void statusShowsKeyFacts() {
        String s = MessageFormatter.status(REPORT);
        assertThat(s).contains("зона 117", "44.6", "ур. 21", "HP 94%", "мана 80%", "питомец 95%", "боеприпасов 1517",
                "grind: running", "убито 25", "добыча 11", "смертей 1", "добыча собрана 11", "мёртв 4", "труп не найден");
        assertThat(s).contains("⚔ в бою");
    }

    @Test
    void statusHandlesDeadIdleAndGarbage() {
        assertThat(MessageFormatter.status("{\"dead\":true,\"job\":{\"status\":\"idle\"},\"hpPct\":0}")).contains("💀 мёртв", "задачи нет");
        assertThat(MessageFormatter.status("не json")).isEqualTo("не json");
        assertThat(MessageFormatter.status(null)).isEqualTo("нет данных");
    }

    @Test
    void toolResultFormats() {
        assertThat(MessageFormatter.toolResult("{\"result\":\"запущено: grind\"}")).isEqualTo("запущено: grind");
        assertThat(MessageFormatter.toolResult("{\"result\":\"ок\",\"mode\":\"radius\",\"spots\":3}")).contains("ок", "mode: radius", "spots: 3");
        assertThat(MessageFormatter.toolResult("")).isEqualTo("готово");
        assertThat(MessageFormatter.toolResult("[1,2]")).contains("1");
    }

    @Test
    void notifications() {
        assertThat(MessageFormatter.death(Map.of("source", "bot", "zone", 117, "x", 44.6, "y", 63.2, "hpBefore", 0.05)))
                .contains("погиб", "бота", "117", "44.6", "5%");
        assertThat(MessageFormatter.jobEnd(Map.of("name", "grind", "status", "dead", "result", "персонаж погиб",
                "stats", Map.of("kills", 7, "loots", 3)))).contains("💀", "Гринд завершён", "персонаж погиб", "убито 7");
        assertThat(MessageFormatter.jobEnd(Map.of("name", "loot", "status", "done"))).isNull();          // служебная задача
        assertThat(MessageFormatter.sell(Map.of("sold", 5, "moneyDelta", 1114, "ok", true))).contains("Продано", "5", "11с 14м");
        assertThat(MessageFormatter.sell(Map.of("sold", 0, "ok", false))).contains("не завершена");
    }

    @Test
    void coinsFormat() {
        assertThat(MessageFormatter.coins(123456)).isEqualTo("12з 34с 56м");
        assertThat(MessageFormatter.coins(50)).isEqualTo("50м");
    }
}
