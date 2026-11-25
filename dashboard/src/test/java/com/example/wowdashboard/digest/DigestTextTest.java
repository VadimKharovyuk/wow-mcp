package com.example.wowdashboard.digest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DigestTextTest {

    private static Map<String, Object> part(String from, String to, int found, int missed, int mountOk, int mountFail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from); p.put("to", to); p.put("fights", 16); p.put("avg_fight_sec", 38.0); p.put("fights_bad", 0);
        p.put("attacker_found", found); p.put("attacker_missed", missed); p.put("turn_found", 1); p.put("turn_missed", 4);
        p.put("mount_ok", mountOk); p.put("mount_fail", mountFail); p.put("approach_ok", 14); p.put("approach_fail", 2);
        p.put("deaths", 0); p.put("warn_error", 3);
        return p;
    }

    private static Map<String, Object> sample() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("minutes", 60);
        d.put("now", "2026-11-26 01:00:00");
        d.put("profiles", List.of("paladin"));
        d.put("game", Map.of("hours", 1, "kills", Map.of("bot", 12), "deaths", Map.of(), "xpGained", 5000, "botSeconds", 3000));
        d.put("fights", Map.of("n", 10, "avg_sec", 31.5, "p90_sec", 80.0, "avg_hp_loss_pct", 18.0, "avg_attackers", 1.5));
        d.put("rates", Map.of("fightsOkPct", 97.9, "attackerFoundPct", 57.4, "mountedPct", 100.0, "deathsPerHour", 0.0));
        d.put("parts", List.of(part("00:00", "00:20", 0, 12, 0, 8), part("00:20", "00:40", 20, 5, 8, 0), part("00:40", "01:00", 19, 12, 8, 0)));
        d.put("problems", List.of(Map.of("category", "approach", "level", "warn", "msg", "подход: нет сближения", "n", 7, "first", "00:01:00", "last", "01:00:00")));
        d.put("newInPeriod", List.of(Map.of("category", "fight", "level", "info", "msg", "диагностика", "n", 9),
                Map.of("category", "bridge", "level", "warn", "msg", "старый Lua", "n", 4)));
        d.put("flags", List.of("Улучшение: посадка на коня при перебежке выросло с 0% до 100% (первая → последняя часть) — похоже, исправлено."));
        d.put("verdict", Map.of("working", List.of("Убийств за период: {bot=12}, смертей: 0, опыта: 5000."), "changed", List.of(),
                "significant", List.of("Низкая эффективность: доворот — удалось 17%."), "rare", List.of("[press_fail/warn] не лицом ×6 (01:00:00–01:30:00)"), "threshold", 10));
        d.put("noise", Map.of("spellNotOnBars", 3466));
        d.put("normal", List.of(Map.of("category", "fight", "msg", "бой окончен", "n", 47)));
        return d;
    }

    @Test
    void rendersRatesAndTrendBeforeProblems() {
        String t = DigestText.render(sample(), 12000);
        assertThat(t).contains("последние 60 мин").contains("Профили: [paladin]")
                .contains("поиск атакующего после убийства цели нашёл его: 57.4%")
                .contains("бот сел на коня: 100.0%")
                .contains("00:00–00:20: боёв 16").contains("конь сел/не сел 0/8").contains("конь сел/не сел 8/0");
        assertThat(t.indexOf("Показатели")).isLessThan(t.indexOf("Тренд по частям"));
        assertThat(t.indexOf("Тренд по частям")).isLessThan(t.indexOf("Проблемы (warn/error)"));
    }

    @Test
    void fullTextStartsWithVerdictAndModelTextOmitsNoise() {
        Map<String, Object> d = sample();
        String full = DigestText.render(d, 12000);
        assertThat(full).contains("Итог (посчитан программой):").contains("Работает:").contains("Значимые проблемы:");
        assertThat(full.indexOf("Итог (посчитан программой)")).isLessThan(full.indexOf("Показатели за весь период"));
        String model = DigestText.renderForModel(d, 12000);
        assertThat(model).contains("Значимые проблемы (только про них нужны гипотезы):").contains("Низкая эффективность: доворот")
                .contains("Тренд по частям").contains("ошибки нажатия: не лицом")
                .doesNotContain("Нормальные события").doesNotContain("spell not on bars").doesNotContain("Показатели за весь период");
    }

    @Test
    void flagsComeBeforeTrendAndEmptyFlagsAreStated() {
        String t = DigestText.render(sample(), 12000);
        assertThat(t).contains("Выводы по цифрам (посчитаны программой").contains("Улучшение: посадка на коня");
        assertThat(t.indexOf("Выводы по цифрам")).isLessThan(t.indexOf("Тренд по частям"));
        Map<String, Object> empty = sample();
        empty.put("flags", List.of());
        assertThat(DigestText.render(empty, 12000)).contains("заметных отклонений по показателям нет");
    }

    @Test
    void noiseAndNormalEventsAreMarked() {
        String t = DigestText.render(sample(), 12000);
        assertThat(t).contains("Известный шум (игнорировать").contains("«spell not on bars» ×3466")
                .contains("Нормальные события и диагностика (только для справки, это не проблемы)").contains("[fight] бой окончен ×47");
        // нормальные события не попадают в проблемы
        assertThat(t.substring(t.indexOf("Проблемы (warn/error)"), t.indexOf("Известный шум"))).doesNotContain("бой окончен");
    }

    @Test
    void newInPeriodShowsOnlyWarnAndError() {
        String t = DigestText.render(sample(), 12000);
        assertThat(t).contains("[bridge/warn] старый Lua ×4").doesNotContain("диагностика ×9");
    }

    @Test
    void longTextIsTruncated() {
        String t = DigestText.render(sample(), 200);
        assertThat(t.length()).isLessThanOrEqualTo(230);
        assertThat(t).endsWith("(сводка обрезана)");
    }
}
