package com.example.wowmcp.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный клиент Telegram Bot API на {@link HttpClient} (без сторонних библиотек).
 * Токен входит в URL запроса, поэтому он нигде не логируется ({@link #redact}).
 */
public class TelegramApi {

    private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Лимит Telegram на длину сообщения — 4096 символов; оставляем запас под многоточие. */
    static final int MAX_TEXT = 4000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String token;

    public TelegramApi(String apiUrl, String token) {
        this.baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
    }

    /** Ошибка ответа Telegram (ok = false) — код и описание. */
    public static class ApiException extends IOException {
        private final int code;

        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** Новые обновления с позиции offset; ждёт до timeoutSec секунд (long polling). */
    public List<JsonNode> getUpdates(long offset, int timeoutSec) throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("offset", offset);
        params.put("timeout", timeoutSec);
        params.put("allowed_updates", List.of("message"));
        JsonNode result = call("getUpdates", params, Duration.ofSeconds(timeoutSec + 15));
        List<JsonNode> out = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode n : result) out.add(n);
        }
        return out;
    }

    /** Отправить обычный текст (без разметки: не нужно экранировать символы). replyMarkup — клавиатура или null. */
    public void sendMessage(long chatId, String text, Object replyMarkup) throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", truncate(text));
        if (replyMarkup != null) params.put("reply_markup", replyMarkup);
        call("sendMessage", params, Duration.ofSeconds(20));
    }

    /** Проверка токена: имя бота или исключение. */
    public String getMe() throws IOException, InterruptedException {
        JsonNode me = call("getMe", Map.of(), Duration.ofSeconds(15));
        return me == null ? "" : me.path("username").asText("");
    }

    static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…";
    }

    JsonNode call(String method, Map<String, Object> params, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(params)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException(redact(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ApiException(response.statusCode(), "ответ Telegram не JSON (HTTP " + response.statusCode() + ")");
        }
        if (!body.path("ok").asBoolean(false)) {
            throw new ApiException(body.path("error_code").asInt(response.statusCode()),
                    redact(body.path("description").asText("ошибка Telegram")));
        }
        return body.path("result");
    }

    /** Убирает токен из любого текста, который может попасть в лог. */
    String redact(String text) {
        if (text == null) return "";
        return token == null || token.isBlank() ? text : text.replace(token, "***");
    }
}
