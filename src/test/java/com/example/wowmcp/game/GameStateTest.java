package com.example.wowmcp.game;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameStateTest {

    @Test
    void parsesBotReport() {
        String json = """
                {"job":{"name":"go","status":"done","started":1730000000000,"result":"дошёл"},
                 "hpPct":87,"x":12.34,"y":56.78,"facing":1.2,"hp":870,"hpMax":1000,
                 "fight":false,"time":12345.6,"target":true,"targetHp":120,"enemy":true}
                """;

        GameState state = GameState.parse(json);

        assertThat(state.job().name()).isEqualTo("go");
        assertThat(state.job().status()).isEqualTo("done");
        assertThat(state.job().result()).isEqualTo("дошёл");
        assertThat(state.hpPct()).isEqualTo(87);
        assertThat(state.x()).isEqualTo(12.34);
        assertThat(state.y()).isEqualTo(56.78);
        assertThat(state.fight()).isFalse();
        assertThat(state.target()).isTrue();
        assertThat(state.targetHp()).isEqualTo(120);
        assertThat(state.enemy()).isTrue();
    }

    @Test
    void ignoresExtraFieldsFromFinishedJob() {
        String json = """
                {"job":{"name":"fight","status":"done","started":1,"result":"бой окончен",
                        "x":10.0,"y":20.0,"hp":500,"hpMax":1000},
                 "hpPct":50,"x":10.0,"y":20.0,"facing":0.0,"hp":500,"hpMax":1000,
                 "fight":false,"time":42.0,"target":false,"targetHp":0,"enemy":false}
                """;

        GameState state = GameState.parse(json);

        assertThat(state.job().status()).isEqualTo("done");
        assertThat(state.hpPct()).isEqualTo(50);
    }
}
