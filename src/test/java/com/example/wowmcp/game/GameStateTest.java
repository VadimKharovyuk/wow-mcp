package com.example.wowmcp.game;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameStateTest {

    @Test
    void parsesBotReport() {
        String json = """
                {"job":{"name":"go","status":"done","started":1730000000000,"result":"дошёл"},
                 "hpPct":87,"x":12.34,"y":56.78,"facing":1.2,"hp":870,"hpMax":1000,
                 "fight":false,"time":12345.6,"target":true,"targetHp":120,"enemy":true,
                 "ready":5,"err":2,"errTime":1234.5,"moDead":true,"lootN":3}
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
        assertThat(state.ready()).isEqualTo(5);
        assertThat(state.err()).isEqualTo(2);
        assertThat(state.errTime()).isEqualTo(1234.5);
        assertThat(state.moDead()).isTrue();
        assertThat(state.lootN()).isEqualTo(3);
    }

    @Test
    void ignoresExtraFieldsFromFinishedJob() {
        String json = """
                {"job":{"name":"fight","status":"done","started":1,"result":"бой окончен",
                        "x":10.0,"y":20.0,"hp":500,"hpMax":1000,"ready":3,"lootN":0},
                 "hpPct":50,"x":10.0,"y":20.0,"facing":0.0,"hp":500,"hpMax":1000,
                 "fight":false,"time":42.0,"target":false,"targetHp":0,"enemy":false,
                 "ready":0,"err":0,"errTime":0.0,"moDead":false,"lootN":0}
                """;

        GameState state = GameState.parse(json);

        assertThat(state.job().status()).isEqualTo("done");
        assertThat(state.job().ready()).isEqualTo(3);
        assertThat(state.hpPct()).isEqualTo(50);
        assertThat(state.lootN()).isZero();
    }
}
