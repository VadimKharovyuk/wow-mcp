package com.example.wowdashboard.digest;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Сводка в виде короткого текста. Две версии: полная (кнопка «Сводка» на странице: итог, показатели, тренд, проблемы, нормальные
 * события) и короткая для модели (только значимые проблемы, выводы по цифрам и тренд — меньше места, чтобы модель не достраивала лишнего).
 */
public final class DigestText {

    /** Потолок длины сводки для страницы (символов). Для модели потолок задаётся настройкой analyzer.max-prompt-chars. */
    public static final int DEFAULT_MAX_CHARS = 12000;

    private DigestText() {
    }

    /** Полная сводка для страницы. */
    public static String render(Map<String, Object> d, int maxChars) {
        return build(d, maxChars, false);
    }

    /** Короткая сводка для модели: без нормальных событий и шума. */
    public static String renderForModel(Map<String, Object> d, int maxChars) {
        return build(d, maxChars, true);
    }

    private static String build(Map<String, Object> d, int maxChars, boolean forModel) {
        StringBuilder sb = new StringBuilder();
        sb.append("Период: последние ").append(d.get("minutes")).append(" мин, на ").append(d.get("now")).append(". Профили: ")
                .append(d.get("profiles")).append('\n');
        Map<String, Object> verdict = map(d.get("verdict"));
        if (!forModel && !verdict.isEmpty()) {
            sb.append("\nИтог (посчитан программой):\n").append(Verdict.render(verdict)).append('\n');
        }
        Map<String, Object> game = map(d.get("game"));
        if (!game.isEmpty() && !forModel) {
            sb.append("\nИтоги игры (за ").append(d.get("minutes")).append(" мин): убийства ").append(game.get("kills"))
                    .append(", смерти ").append(game.get("deaths")).append(", опыт ").append(game.get("xpGained"))
                    .append(", бот работал ").append(game.get("botSeconds")).append(" с\n");
        }
        Map<String, Object> f = map(d.get("fights"));
        if (!f.isEmpty() && num(f.get("n")) > 0) {
            sb.append("Бои: ").append(f.get("n")).append(", средняя длительность ").append(f.get("avg_sec")).append(" с, p90 ")
                    .append(f.get("p90_sec")).append(" с, средняя потеря HP ").append(f.get("avg_hp_loss_pct")).append("%, врагов в среднем ")
                    .append(f.get("avg_attackers")).append('\n');
        }
        Map<String, Object> rates = map(d.get("rates"));
        if (!rates.isEmpty() && !forModel) {
            sb.append("\nПоказатели за весь период:\n");
            sb.append("- бои закончились нормально: ").append(p(rates.get("fightsOkPct"))).append('\n');
            sb.append("- поиск атакующего после убийства цели нашёл его: ").append(p(rates.get("attackerFoundPct"))).append('\n');
            sb.append("- доворот к цели вне экрана нашёл её: ").append(p(rates.get("turnFoundPct"))).append('\n');
            sb.append("- перебежки: бот сел на коня: ").append(p(rates.get("mountedPct"))).append('\n');
            sb.append("- подход к цели дошёл: ").append(p(rates.get("approachOkPct"))).append('\n');
            sb.append("- смертей в час: ").append(rates.get("deathsPerHour")).append('\n');
        }
        if (forModel) {
            section(sb, "Значимые проблемы (только про них нужны гипотезы)", strings(verdict.get("significant")), s -> s);
        }
        Object fl = d.get("flags");
        if (fl instanceof List<?> flags) {
            sb.append("\nВыводы по цифрам (посчитаны программой — не пересчитывай и не меняй «выросло/упало»):\n");
            if (flags.isEmpty()) sb.append("- заметных отклонений по показателям нет\n");
            for (Object f1 : flags) sb.append("- ").append(f1).append('\n');
        }
        List<Map<String, Object>> parts = rows(d.get("parts"));
        if (!parts.isEmpty()) {
            sb.append("\nТренд по частям периода (от старой к новой; видно, что исправилось или появилось):\n");
            for (Map<String, Object> r : parts) {
                sb.append("- ").append(r.get("from")).append("–").append(r.get("to")).append(": боёв ").append(r.get("fights"))
                        .append(" (средний ").append(r.get("avg_fight_sec")).append(" с, не ок ").append(r.get("fights_bad"))
                        .append("); атакующий найден/не найден ").append(r.get("attacker_found")).append("/").append(r.get("attacker_missed"))
                        .append("; доворот нашёл/не нашёл ").append(r.get("turn_found")).append("/").append(r.get("turn_missed"))
                        .append("; конь сел/не сел ").append(r.get("mount_ok")).append("/").append(r.get("mount_fail"))
                        .append("; подход ок/провал ").append(r.get("approach_ok")).append("/").append(r.get("approach_fail"))
                        .append("; смертей ").append(r.get("deaths")).append("; warn+error ").append(r.get("warn_error"))
                        .append("; ошибки нажатия: не лицом ").append(r.get("press_not_facing")).append(", слишком далеко ").append(r.get("press_too_far"))
                        .append(", нет реакции ").append(r.get("press_no_reaction")).append(", другая ").append(r.get("press_other")).append('\n');
            }
        }
        if (!forModel) {
            section(sb, "Исходы боёв", rows(d.get("fightResults")), r -> r.get("result") + " ×" + r.get("n"));
            section(sb, "Проблемы (warn/error), по частоте", rows(d.get("problems")),
                    r -> "[" + r.get("category") + "/" + r.get("level") + "] " + r.get("msg") + " ×" + r.get("n") + " (" + r.get("first") + "–" + r.get("last") + ")");
            section(sb, "Новые предупреждения и ошибки за период (не было в предыдущем такой же длины)",
                    rows(d.get("newInPeriod")).stream().filter(r -> "warn".equals(r.get("level")) || "error".equals(r.get("level"))).toList(),
                    r -> "[" + r.get("category") + "/" + r.get("level") + "] " + r.get("msg") + " ×" + r.get("n"));
            section(sb, "Причины провала подхода", rows(d.get("approachFails")), r -> r.get("why") + " ×" + r.get("n"));
            section(sb, "Смерти", rows(d.get("deaths")), r -> r.get("at") + " зона " + r.get("zone") + ": " + r.get("msg"));
            section(sb, "Ошибки Lua и моста игра↔бот", rows(d.get("luaAndBridge")),
                    r -> "[" + r.get("category") + "/" + r.get("level") + "] " + r.get("msg") + " ×" + r.get("n"));
            Map<String, Object> noise = map(d.get("noise"));
            if (num(noise.get("spellNotOnBars")) > 0) {
                sb.append("\nИзвестный шум (игнорировать, умения без клавиши в ротации оставлены намеренно): «spell not on bars» ×")
                        .append(noise.get("spellNotOnBars")).append('\n');
            }
            section(sb, "Нормальные события и диагностика (только для справки, это не проблемы)", rows(d.get("normal")),
                    r -> "[" + r.get("category") + "] " + r.get("msg") + " ×" + r.get("n"));
        }
        String s = sb.toString();
        return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars - 30)) + "\n…(сводка обрезана)";
    }

    private static <T> void section(StringBuilder sb, String title, List<T> rows, Function<T, String> line) {
        if (rows.isEmpty()) return;
        sb.append('\n').append(title).append(":\n");
        for (T r : rows) sb.append("- ").append(line.apply(r)).append('\n');
    }

    private static String p(Object o) {
        return o == null ? "нет данных" : o + "%";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
