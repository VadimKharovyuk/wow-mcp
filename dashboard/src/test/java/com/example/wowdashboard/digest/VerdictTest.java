package com.example.wowdashboard.digest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictTest {

    private static Map<String, Object> problem(String msg, int n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("category", "press_fail"); p.put("level", "warn"); p.put("msg", msg); p.put("n", n); p.put("first", "01:00:00"); p.put("last", "01:30:00");
        return p;
    }

    private static Map<String, Object> digest(int fights, List<Map<String, Object>> problems, List<String> flags, Map<String, Object> rates) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("game", Map.of("kills", Map.of("bot", 124), "deaths", Map.of(), "xpGained", 200000));
        d.put("fights", Map.of("n", fights, "avg_sec", 34.3));
        d.put("rates", rates);
        d.put("flags", flags);
        d.put("problems", problems);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> v, String key) {
        return (List<String>) v.get(key);
    }

    @Test
    void rareWarningsAreNotProblemsAndNoModelNeeded() {
        // 42 боя → порог max(10, 5) = 10; ×6, ×3, ×1 — редкие
        Map<String, Object> v = Verdict.build(digest(42, List.of(problem("не лицом", 6), problem("слишком далеко", 3), problem("нет сближения", 1)),
                List.of(), Map.of("attackerFoundPct", 78.4, "approachOkPct", 97.1, "mountedPct", 100.0)));
        assertThat(list(v, "significant")).isEmpty();
        assertThat(list(v, "rare")).hasSize(3);
        assertThat(v.get("threshold")).isEqualTo(10L);
        assertThat(list(v, "working")).anyMatch(s -> s.contains("124")).anyMatch(s -> s.contains("Подход к цели дошёл: 97.1%"))
                .anyMatch(s -> s.contains("Посадка на коня при перебежке удалась: 100.0%"));
        String text = Verdict.render(v);
        assertThat(text).contains("Значимые проблемы:\n- значимых проблем нет.").contains("Редкие предупреждения (не проблемы");
    }

    @Test
    void thresholdGrowsWithFightCount() {
        // 200 боёв → порог 20: ×15 — ещё редкое, ×25 — значимое
        Map<String, Object> v = Verdict.build(digest(200, List.of(problem("A", 25), problem("B", 15)), List.of(), Map.of()));
        assertThat(list(v, "significant")).hasSize(1).first().asString().contains("A ×25");
        assertThat(list(v, "rare")).hasSize(1);
        assertThat(v.get("threshold")).isEqualTo(20L);
    }

    @Test
    void flagsGoToChangedAndSignificantByType() {
        Map<String, Object> v = Verdict.build(digest(40, List.of(), List.of(
                "Улучшение: посадка на коня при перебежке выросло с 0% до 100% (первая → последняя часть) — похоже, исправлено.",
                "Низкая эффективность: доворот к цели вне экрана — удалось 17% (4 из 24) за весь период.",
                "Провал в середине периода: поиск атакующего после убийства цели 62% против 85% и 100% в первой и последней частях.",
                "Смерти: 2 за период."), Map.of("turnFoundPct", 17.0)));
        assertThat(list(v, "changed")).hasSize(2);                         // улучшение и провал
        assertThat(list(v, "significant")).hasSize(3);                     // низкая эффективность, провал, смерти
        assertThat(list(v, "working")).noneMatch(s -> s.contains("Доворот"));   // низкая доля не попадает в «Работает»
    }

    @Test
    void lowRatesAreNotListedAsWorking() {
        Map<String, Object> v = Verdict.build(digest(40, List.of(), List.of(), Map.of("attackerFoundPct", 55.9, "mountedPct", 58.7, "approachOkPct", 86.8)));
        assertThat(list(v, "working")).noneMatch(s -> s.contains("Поиск атакующего")).noneMatch(s -> s.contains("Посадка"))
                .anyMatch(s -> s.contains("Подход к цели дошёл: 86.8%"));
    }
}
