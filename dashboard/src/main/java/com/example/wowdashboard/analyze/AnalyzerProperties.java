package com.example.wowdashboard.analyze;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки разбора журнала моделью (Ollama), раздел {@code analyzer} в application.yml.
 *
 * @param ollamaUrl      адрес Ollama (по умолчанию http://localhost:11434)
 * @param model          модель по умолчанию (qwen3:8b)
 * @param models         модели, которые можно выбрать на странице (для сравнения качества разбора)
 * @param numCtx         размер контекста запроса в токенах; Ollama по умолчанию режет до 2–4 тыс., поэтому задаём явно
 * @param timeoutSeconds сколько ждать ответа модели
 * @param maxPromptChars потолок длины сводки, которую отдаём модели (символов)
 */
@ConfigurationProperties(prefix = "analyzer")
public record AnalyzerProperties(String ollamaUrl, String model, java.util.List<String> models, Integer numCtx, Integer timeoutSeconds, Integer maxPromptChars) {

    public AnalyzerProperties {
        if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = "http://localhost:11434";
        if (model == null || model.isBlank()) model = "qwen3:8b";
        models = new java.util.ArrayList<>(models == null ? java.util.List.<String>of() : models);
        if (!models.contains(model)) models.add(0, model);
        models = java.util.List.copyOf(models);
        if (numCtx == null || numCtx < 2048) numCtx = 8192;
        if (timeoutSeconds == null || timeoutSeconds < 10) timeoutSeconds = 240;
        if (maxPromptChars == null || maxPromptChars < 2000) maxPromptChars = 12000;
    }
}
