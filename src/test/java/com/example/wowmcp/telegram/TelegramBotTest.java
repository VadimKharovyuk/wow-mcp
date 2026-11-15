package com.example.wowmcp.telegram;

import com.example.wowmcp.db.LeaderLock;
import com.example.wowmcp.telegram.CommandRouter.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramBotTest {

    private static final long OWNER = 111L, STRANGER = 222L;
    private TelegramApi api;
    private CommandExecutor executor;
    private LeaderLock leader;
    private TelegramBot bot;

    @BeforeEach
    void setUp() {
        api = mock(TelegramApi.class);
        executor = mock(CommandExecutor.class);
        leader = mock(LeaderLock.class);
        when(leader.isLeader()).thenReturn(true);
        TelegramProperties props = new TelegramProperties("tok", List.of(OWNER), true, null);
        bot = new TelegramBot(props, leader, executor);
        bot.attach(api);
    }

    private static JsonNode update(long chat, String text) {
        return JsonMapper.builder().build().readTree(
                "{\"update_id\":1,\"message\":{\"text\":\"" + text + "\",\"chat\":{\"id\":" + chat + "}}}");
    }

    @Test
    void ownerCommandIsExecutedAndAnswered() throws Exception {
        when(executor.execute(any(Command.class))).thenReturn("ответ");
        bot.handleUpdate(update(OWNER, "статус"));
        verify(api, timeout(2000)).sendMessage(eq(OWNER), eq("ответ"), any());
    }

    @Test
    void strangerIsIgnoredExceptIdCommand() throws Exception {
        bot.handleUpdate(update(STRANGER, "качайся"));
        bot.handleUpdate(update(STRANGER, "стоп"));
        verify(executor, never()).execute(any());
        verify(api, never()).sendMessage(eq(STRANGER), any(), any());

        bot.handleUpdate(update(STRANGER, "/id"));
        verify(api).sendMessage(eq(STRANGER), contains("222"), any());          // id нужен, чтобы вписать его в настройки
        verify(executor, never()).execute(any());
    }

    @Test
    void strangerStartGetsHintOnlyOncePerWindow() throws Exception {
        bot.handleUpdate(update(STRANGER, "/start"));
        bot.handleUpdate(update(STRANGER, "/start"));
        verify(api, org.mockito.Mockito.times(1)).sendMessage(eq(STRANGER), contains("/id"), any());
    }

    @Test
    void unknownCommandDoesNotTouchTheGame() throws Exception {
        bot.handleUpdate(update(OWNER, "абракадабра"));
        verify(api).sendMessage(eq(OWNER), contains("Не понял"), any());
        verify(executor, never()).execute(any());
    }

    @Test
    void broadcastOnlyFromLeader() throws Exception {
        bot.broadcast("💀 умер");
        verify(api).sendMessage(eq(OWNER), eq("💀 умер"), eq(null));
        when(leader.isLeader()).thenReturn(false);
        bot.broadcast("ещё");
        verify(api, never()).sendMessage(anyLong(), eq("ещё"), any());
    }

    @Test
    void propertiesRequireTokenAndRespectSwitch() {
        org.assertj.core.api.Assertions.assertThat(new TelegramProperties(null, null, null, null).configured()).isFalse();
        org.assertj.core.api.Assertions.assertThat(new TelegramProperties("PUT_BOT_TOKEN_HERE", null, null, null).configured()).isFalse();
        org.assertj.core.api.Assertions.assertThat(new TelegramProperties("123:abc", null, false, null).configured()).isFalse();
        org.assertj.core.api.Assertions.assertThat(new TelegramProperties("123:abc", null, null, null).configured()).isTrue();
        org.assertj.core.api.Assertions.assertThat(new TelegramProperties("123:abc", null, null, null).apiUrl()).isEqualTo("https://api.telegram.org");
    }
}
