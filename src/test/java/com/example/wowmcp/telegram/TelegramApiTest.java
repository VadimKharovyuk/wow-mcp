package com.example.wowmcp.telegram;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramApiTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String response = "{\"ok\":true,\"result\":[]}";
    private volatile int status = 200;
    private TelegramApi api;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        api = new TelegramApi("http://127.0.0.1:" + server.getAddress().getPort(), "SECRET:TOKEN");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getUpdatesParsesUpdatesAndSendsOffset() throws Exception {
        response = "{\"ok\":true,\"result\":[{\"update_id\":7,\"message\":{\"text\":\"статус\",\"chat\":{\"id\":42}}}]}";
        List<JsonNode> updates = api.getUpdates(5, 0);
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).path("message").path("text").asText()).isEqualTo("статус");
        assertThat(paths.get(0)).isEqualTo("/botSECRET:TOKEN/getUpdates");
        assertThat(bodies.get(0)).contains("\"offset\":5").contains("\"timeout\":0");
    }

    @Test
    void sendMessageSendsChatTextAndKeyboard() throws Exception {
        response = "{\"ok\":true,\"result\":{}}";
        api.sendMessage(42, "привет", TelegramBot.KEYBOARD);
        assertThat(paths.get(0)).endsWith("/sendMessage");
        assertThat(bodies.get(0)).contains("\"chat_id\":42").contains("привет").contains("resize_keyboard");
    }

    @Test
    void longTextIsTruncated() {
        assertThat(TelegramApi.truncate("x".repeat(5000)).length()).isLessThan(4100);
        assertThat(TelegramApi.truncate("коротко")).isEqualTo("коротко");
    }

    @Test
    void errorResponseBecomesApiExceptionWithoutToken() {
        response = "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized SECRET:TOKEN\"}";
        status = 401;
        assertThatThrownBy(() -> api.getUpdates(0, 0))
                .isInstanceOfSatisfying(TelegramApi.ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(401);
                    assertThat(e.getMessage()).doesNotContain("SECRET:TOKEN").contains("***");
                });
    }

    @Test
    void connectionErrorMessageDoesNotLeakToken() {
        TelegramApi dead = new TelegramApi("http://127.0.0.1:1", "SECRET:TOKEN");
        assertThatThrownBy(() -> dead.getUpdates(0, 0)).isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET:TOKEN"));
    }

    @Test
    void redactReplacesToken() {
        assertThat(api.redact("url /botSECRET:TOKEN/x")).isEqualTo("url /bot***/x");
        assertThat(new ArrayList<>(List.of(api.redact(null)))).containsExactly("");
    }
}
