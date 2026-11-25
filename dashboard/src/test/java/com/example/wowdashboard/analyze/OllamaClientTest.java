package com.example.wowdashboard.analyze;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaClientTest {

    @Test
    void extractsContent() {
        assertThat(OllamaClient.extractContent(Map.of("message", Map.of("role", "assistant", "content", " Ответ ")))).isEqualTo("Ответ");
    }

    @Test
    void stripsThinkBlock() {
        Map<String, Object> resp = Map.of("message", Map.of("content", "<think>рассуждение\nв две строки</think>\nИтог"));
        assertThat(OllamaClient.extractContent(resp)).isEqualTo("Итог");
    }

    @Test
    void emptyOrMalformedResponse() {
        assertThat(OllamaClient.extractContent(null)).isEmpty();
        assertThat(OllamaClient.extractContent(Map.of())).isEmpty();
        assertThat(OllamaClient.extractContent(Map.of("message", Map.of()))).isEmpty();
    }

    @Test
    void propertiesGetDefaults() {
        AnalyzerProperties p = new AnalyzerProperties(null, " ", null, 100, 1, 10);
        assertThat(p.model()).isEqualTo("qwen3:8b");     // без настройки — запасная модель
        assertThat(p.ollamaUrl()).isEqualTo("http://localhost:11434");
        assertThat(p.numCtx()).isEqualTo(8192);
        assertThat(p.timeoutSeconds()).isEqualTo(240);
        assertThat(p.maxPromptChars()).isEqualTo(12000);
    }

    @Test
    void modelListAlwaysContainsDefaultModel() {
        AnalyzerProperties p = new AnalyzerProperties(null, "qwen3:8b", java.util.List.of("qwen3.5:9b"), null, null, null);
        assertThat(p.models()).containsExactly("qwen3:8b", "qwen3.5:9b");
        assertThat(new AnalyzerProperties(null, "a", java.util.List.of("a", "b"), null, null, null).models()).containsExactly("a", "b");
    }
}
