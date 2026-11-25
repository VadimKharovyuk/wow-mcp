package com.example.wowdashboard.analyze;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Разбор без БД и без Ollama: сводка и модель подменены заглушками. */
class LogAnalyzerTest {

    private static final class FakeModel implements ModelClient {
        final List<String> models = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        String answer = "гипотеза";
        Runnable onCall = () -> { };

        @Override
        public String chat(String system, String user, String model) {
            models.add(model);
            prompts.add(user);
            onCall.run();
            return answer;
        }
    }

    private static Map<String, Object> digest(List<String> significant) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("working", List.of("Убийств за период: {bot=10}, смертей: 0, опыта: 1."));
        verdict.put("changed", List.of());
        verdict.put("significant", significant);
        verdict.put("rare", List.of());
        verdict.put("threshold", 10);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("minutes", 30);
        d.put("now", "2026-11-26 02:00:00");
        d.put("profiles", List.of("paladin"));
        d.put("flags", List.of());
        d.put("parts", List.of());
        d.put("verdict", verdict);
        return d;
    }

    private static LogAnalyzer analyzer(List<String> significant, FakeModel model) {
        return new LogAnalyzer(minutes -> digest(significant), model,
                new AnalyzerProperties(null, "qwen3.5:9b", List.of("qwen3:8b"), null, null, null));
    }

    @Test
    void modelIsNotCalledWhenNoSignificantProblems() {
        FakeModel m = new FakeModel();
        LogAnalyzer.Result r = analyzer(List.of(), m).analyze(30, null);
        assertThat(m.models).isEmpty();
        assertThat(r.text()).contains("Работает:").contains("значимых проблем нет.").contains("Модель не вызывалась");
    }

    @Test
    void modelCommentsOnlySignificantProblemsAndIsMarkedAsHypotheses() {
        FakeModel m = new FakeModel();
        m.answer = "доворот не находит цель — проверить дальность";
        LogAnalyzer.Result r = analyzer(List.of("Низкая эффективность: доворот — удалось 17%."), m).analyze(30, "qwen3:8b");
        assertThat(m.models).containsExactly("qwen3:8b");
        assertThat(m.prompts.get(0)).contains("Низкая эффективность: доворот");
        assertThat(r.text()).contains("Значимые проблемы:").contains("Гипотезы модели qwen3:8b").contains("доворот не находит цель");
        assertThat(r.model()).isEqualTo("qwen3:8b");
    }

    @Test
    void unknownModelFallsBackToDefault() {
        FakeModel m = new FakeModel();
        analyzer(List.of("проблема"), m).analyze(30, "какая-то-другая");
        assertThat(m.models).containsExactly("qwen3.5:9b");
    }

    @Test
    void emptyModelAnswerIsStated() {
        FakeModel m = new FakeModel();
        m.answer = " ";
        assertThat(analyzer(List.of("проблема"), m).analyze(30, null).text()).contains("(модель вернула пустой ответ)");
    }

    @Test
    void secondAnalysisWhileBusyIsRejectedAndLastResultIsKept() {
        FakeModel m = new FakeModel();
        LogAnalyzer a = analyzer(List.of("проблема"), m);
        m.onCall = () -> assertThatThrownBy(() -> a.analyze(30, null)).isInstanceOf(IllegalStateException.class);
        a.analyze(30, null);
        assertThat(a.isBusy()).isFalse();
        assertThat(a.last()).isNotNull();
        assertThat(a.last().minutes()).isEqualTo(30);
    }

    @Test
    void busyFlagIsClearedAfterModelFailure() {
        FakeModel m = new FakeModel();
        m.onCall = () -> { throw new IllegalArgumentException("Ollama недоступна"); };
        LogAnalyzer a = analyzer(List.of("проблема"), m);
        assertThatThrownBy(() -> a.analyze(30, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.isBusy()).isFalse();
    }
}
