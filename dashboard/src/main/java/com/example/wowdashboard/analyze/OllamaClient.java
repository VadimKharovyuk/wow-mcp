package com.example.wowdashboard.analyze;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Клиент Ollama (/api/chat): без потока, без «размышлений», с явным размером контекста; модель выгружается сразу после ответа. */
@Component
class OllamaClient implements ModelClient {

    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    private final RestClient rest;
    private final AnalyzerProperties props;

    OllamaClient(AnalyzerProperties props) {
        this.props = props;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        this.rest = RestClient.builder().baseUrl(props.ollamaUrl()).requestFactory(factory).build();
    }

    @Override
    public String chat(String system, String user, String model) {
        try {
            return extractContent(post(system, user, model, true));
        } catch (RestClientResponseException e) {
            // модель без поддержки «think» отвечает ошибкой 400 — повторяем без этого поля
            if (e.getStatusCode().value() == 400 && String.valueOf(e.getResponseBodyAsString()).contains("think")) {
                return extractContent(post(system, user, model, false));
            }
            throw e;
        }
    }

    private Map<?, ?> post(String system, String user, String model, boolean withThink) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        if (withThink) body.put("think", false);
        body.put("keep_alive", 0);                               // выгрузить модель из памяти сразу после ответа
        body.put("options", Map.of("num_ctx", props.numCtx(), "temperature", 0.2));
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        return rest.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    /** Текст ответа из JSON Ollama; блок «размышлений» (если модель его всё же прислала) вырезается. */
    static String extractContent(Map<?, ?> resp) {
        if (resp == null || !(resp.get("message") instanceof Map<?, ?> m) || m.get("content") == null) return "";
        return THINK.matcher(String.valueOf(m.get("content"))).replaceAll("").trim();
    }
}
