package com.example.wowdashboard.digest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowdashboard.digest.Nums.num;

/**
 * Детерминированный итог по сводке журнала: что работает, что изменилось, значимые проблемы и редкие предупреждения.
 * Всё считает программа, поэтому числа в отчёте гарантированно сходятся со сводкой. Модель привлекается только за гипотезами
 * по значимым проблемам.
 */
public final class Verdict {

    /** Предупреждение считается значимым, если повторилось не меньше этого числа раз или не меньше этой доли от числа боёв. */
    static final int MIN_COUNT = 10;
    static final double MIN_SHARE_OF_FIGHTS = 0.10;

    private Verdict() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> build(Map<String, Object> d) {
        Map<String, Object> game = d.get("game") instanceof Map<?, ?> g ? (Map<String, Object>) g : Map.of();
        Map<String, Object> fights = d.get("fights") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        Map<String, Object> rates = d.get("rates") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        List<String> flags = d.get("flags") instanceof List<?> l ? (List<String>) l : List.of();
        List<Map<String, Object>> problems = d.get("problems") instanceof List<?> p ? (List<Map<String, Object>>) p : List.of();
        long fightCount = num(fights.get("n"));
        long threshold = Math.max(MIN_COUNT, (long) Math.ceil(fightCount * MIN_SHARE_OF_FIGHTS));

        List<String> working = new ArrayList<>();
        Map<String, Object> kills = game.get("kills") instanceof Map<?, ?> k ? (Map<String, Object>) k : Map.of();
        Map<String, Object> deaths = game.get("deaths") instanceof Map<?, ?> k ? (Map<String, Object>) k : Map.of();
        long deathCount = deaths.values().stream().mapToLong(Nums::num).sum();
        working.add("Убийств за период: " + kills + ", смертей: " + deathCount + ", опыта: " + game.get("xpGained") + ".");
        if (fightCount > 0) {
            working.add("Боёв: " + fightCount + ", средняя длительность " + fights.get("avg_sec") + " с"
                    + (rates.get("fightsOkPct") != null ? ", нормально закончились " + rates.get("fightsOkPct") + "%" : "") + ".");
        }
        goodRate(working, rates, "attackerFoundPct", "Поиск атакующего после убийства цели нашёл его");
        goodRate(working, rates, "approachOkPct", "Подход к цели дошёл");
        goodRate(working, rates, "mountedPct", "Посадка на коня при перебежке удалась");
        goodRate(working, rates, "turnFoundPct", "Доворот к цели вне экрана нашёл её");

        List<String> changed = new ArrayList<>();
        List<String> significant = new ArrayList<>();
        for (String f : flags) {
            boolean problem = f.startsWith("Низкая эффективность") || f.startsWith("Ухудшение") || f.startsWith("Смерти")
                    || f.startsWith("Провал") || f.startsWith("Всплеск");
            if (f.startsWith("Улучшение") || f.startsWith("Ухудшение") || f.startsWith("Провал") || f.startsWith("Всплеск")) changed.add(f);
            if (problem) significant.add(f);
        }
        List<String> rare = new ArrayList<>();
        for (Map<String, Object> pr : problems) {
            String line = "[" + pr.get("category") + "/" + pr.get("level") + "] " + pr.get("msg") + " ×" + pr.get("n")
                    + " (" + pr.get("first") + "–" + pr.get("last") + ")";
            if (num(pr.get("n")) >= threshold) significant.add(line); else rare.add(line);
        }

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("working", working);
        v.put("changed", changed);
        v.put("significant", significant);
        v.put("rare", rare);
        v.put("threshold", threshold);
        return v;
    }

    /** Показатель с долей ≥ 70% — в «Работает»; низкие уходят в значимые проблемы через флаги. */
    private static void goodRate(List<String> out, Map<String, Object> rates, String key, String label) {
        Object v = rates.get(key);
        if (v instanceof Number n && n.doubleValue() >= 70) out.add(label + ": " + v + "%.");
    }

    /** Итог текстом для страницы (без модели). */
    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> v) {
        StringBuilder sb = new StringBuilder();
        block(sb, "Работает", (List<String>) v.get("working"), null);
        block(sb, "Что изменилось", (List<String>) v.get("changed"), "заметных изменений между частями периода нет.");
        block(sb, "Значимые проблемы", (List<String>) v.get("significant"), "значимых проблем нет.");
        List<String> rare = (List<String>) v.get("rare");
        if (!rare.isEmpty()) block(sb, "Редкие предупреждения (не проблемы; порог значимости " + v.get("threshold") + " раз)", rare, null);
        return sb.toString().stripTrailing();
    }

    private static void block(StringBuilder sb, String title, List<String> lines, String ifEmpty) {
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(title).append(":\n");
        if (lines.isEmpty()) sb.append("- ").append(ifEmpty == null ? "нет" : ifEmpty);
        else for (int i = 0; i < lines.size(); i++) sb.append("- ").append(lines.get(i)).append(i + 1 < lines.size() ? "\n" : "");
    }
}
