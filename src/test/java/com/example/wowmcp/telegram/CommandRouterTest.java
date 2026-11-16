package com.example.wowmcp.telegram;

import com.example.wowmcp.telegram.CommandRouter.Command;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRouterTest {

    private static Command p(String s) {
        return CommandRouter.parse(s);
    }

    @Test
    void russianPhrasesFromAgentsTable() {
        assertThat(p("статус").name()).isEqualTo("status");
        assertThat(p("  Статус ").name()).isEqualTo("status");
        assertThat(p("стоп").name()).isEqualTo("stop");
        assertThat(p("продай").name()).isEqualTo("sell");
        assertThat(p("Воскресни").name()).isEqualTo("revive");
        assertThat(p("призови питомца").name()).isEqualTo("pet");
        assertThat(p("/pet").name()).isEqualTo("pet");
        assertThat(p("сядь на коня").name()).isEqualTo("mount");
        assertThat(p("слезь").name()).isEqualTo("dismount");
        assertThat(p("покажи дороги").name()).isEqualTo("roads_list");
        assertThat(p("дорога 2").name()).isEqualTo("go_road");
        assertThat(p("дорога 2").arg()).isEqualTo("2");
        assertThat(p("иди качайся").name()).isEqualTo("hunt");
        assertThat(p("помощь").name()).isEqualTo("help");
        assertThat(p("перезагрузи бота").name()).isEqualTo("reload");
        assertThat(p("построй дороги").name()).isEqualTo("roads");
        assertThat(p("что-то непонятное").name()).isEqualTo(CommandRouter.UNKNOWN);
    }

    @Test
    void grindVariants() {
        Command c = p("качайся");
        assertThat(c.name()).isEqualTo("grind");
        assertThat(c.flag()).isTrue();                 // с лутом
        assertThat(c.arg()).isNull();                  // radius
        assertThat(c.kills()).isNull();
        assertThat(p("качайся без лута").flag()).isFalse();
        assertThat(p("качайся по точкам").arg()).isEqualTo("patrol");
        Command limited = p("качайся 50 убийств 30 минут");
        assertThat(limited.kills()).isEqualTo(50);
        assertThat(limited.minutes()).isEqualTo(30);
        assertThat(p("/grind 10 убийств").kills()).isEqualTo(10);
        assertThat(p("/patrol").arg()).isEqualTo("patrol");
    }

    @Test
    void commandsWithNames() {
        assertThat(p("иди к гарпиям").name()).isEqualTo("go_place");
        assertThat(p("иди к гарпиям").arg()).isEqualTo("гарпиям");
        assertThat(p("Иди в Перекрёсток").arg()).isEqualTo("Перекрёсток");       // регистр и «ё» сохраняются для имени
        assertThat(p("иди по маршруту перекресток-гарпии").name()).isEqualTo("go_route");
        assertThat(p("запомни место Лагерь Таурахо").arg()).isEqualTo("Лагерь Таурахо");
        assertThat(p("запомни торговца Шанью").name()).isEqualTo("remember_vendor");
        assertThat(p("запомни торговца").name()).isEqualTo("remember_vendor_here");
        assertThat(p("стоп запись дорога1").name()).isEqualTo("record_stop");
        assertThat(p("стоп запись дорога1").arg()).isEqualTo("дорога1");
        assertThat(p("стоп запись").name()).isEqualTo("record_stop");
    }

    @Test
    void coordinates() {
        Command go = p("иди 45,5 60");
        assertThat(go.name()).isEqualTo("goto");
        assertThat(go.arg()).isEqualTo("45.5 60");
        assertThat(go.flag()).isTrue();                // с боем
        assertThat(p("беги 10 20").flag()).isFalse();  // без боя
    }

    @Test
    void slashCommands() {
        assertThat(p("/status").name()).isEqualTo("status");
        assertThat(p("/status@my_bot").name()).isEqualTo("status");
        assertThat(p("/start").name()).isEqualTo("help");
        assertThat(p("/id").name()).isEqualTo("id");
        assertThat(p("/goplace гарпии").arg()).isEqualTo("гарпии");
        assertThat(p("/goplace").name()).isEqualTo(CommandRouter.UNKNOWN);
        assertThat(p("/nope").name()).isEqualTo(CommandRouter.UNKNOWN);
        assertThat(p(null).name()).isEqualTo(CommandRouter.UNKNOWN);
    }
}
