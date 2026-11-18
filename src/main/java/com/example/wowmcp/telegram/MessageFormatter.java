package com.example.wowmcp.telegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Тексты для Telegram: статус персонажа, результаты инструментов, уведомления о событиях. Чистые функции. */
public final class MessageFormatter {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private MessageFormatter() {
    }

    /** Статус из JSON {@code bot.report()} (то же, что get_state). */
    public static String status(String reportJson) {
        JsonNode r;
        try {
            r = JSON.readTree(reportJson);
        } catch (RuntimeException e) {
            return reportJson == null ? "нет данных" : cut(reportJson, 500);
        }
        List<String> lines = new ArrayList<>();
        String state = r.path("dead").asBoolean() ? "💀 мёртв" : r.path("ghost").asBoolean() ? "👻 дух"
                : r.path("fight").asBoolean() ? "⚔ в бою" : "🟢 жив";
        lines.add(state + " · зона " + r.path("zone").asInt() + " (" + num(r.path("x")) + "; " + num(r.path("y")) + ") · ур. "
                + r.path("pLvl").asInt()
                + (r.path("xpPct").isNumber() ? " · опыт " + r.path("xpPct").asInt() + "% (до след. " + r.path("xpLeftPct").asInt() + "%)" : ""));
        lines.add("❤ HP " + r.path("hpPct").asInt() + "% · 💧 мана " + Math.round(r.path("manaPct").asDouble() * 100) + "%");
        if (r.path("petExists").asBoolean() || r.path("petDead").asBoolean()) {
            lines.add("🐾 питомец " + (r.path("petDead").asBoolean() ? "мёртв" : Math.round(r.path("petHpPct").asDouble() * 100) + "%"));
        }
        if (r.path("ammo").asInt() > 0) {
            lines.add("🏹 боеприпасов " + r.path("ammo").asInt());
        }
        JsonNode job = r.path("job");
        if (!job.isMissingNode() && !"idle".equals(job.path("status").asText("idle"))) {
            String res = job.path("result").asText("");
            lines.add("⚙ " + job.path("name").asText("?") + ": " + job.path("status").asText("") + (res.isEmpty() ? "" : " — " + res));
        } else {
            lines.add("⚙ задачи нет");
        }
        JsonNode g = r.path("grind");
        if (g.isObject()) {
            lines.add((g.path("minutes").isNumber() ? "⏱ " + g.path("minutes").asText() + " мин" + (g.path("killsPerMin").isNumber() ? " · " + g.path("killsPerMin").asText() + " убийств/мин" : "") + "\n" : "")
                    + "🗡 убито " + g.path("kills").asInt() + " · добыча " + g.path("loots").asInt() + " · отдыхов "
                    + g.path("rests").asInt() + (g.path("deaths").asInt() > 0 ? " · смертей " + g.path("deaths").asInt() : ""));
            JsonNode ph = g.path("phasesPct");
            if (ph.isObject() && ph.size() > 0) {
                List<String> parts = new ArrayList<>();
                ph.properties().forEach(e -> parts.add(e.getKey() + " " + e.getValue().asText() + "%"));
                lines.add("⏳ время: " + String.join(" · ", parts));
            }
            JsonNode lr = g.path("lootResults");
            if (lr.isObject() && lr.size() > 0) lines.add("📦 " + compact(lr));
            JsonNode rej = g.path("rejects");
            if (rej.isObject() && rej.size() > 0) lines.add("🚫 отказы: " + compact(rej));
        }
        JsonNode hunt = r.path("hunt");
        if (hunt.isObject()) {
            lines.add("🧭 охота: убито " + hunt.path("kills").asInt() + "/" + hunt.path("target").asInt()
                    + ", мест посещено " + hunt.path("visited").asInt() + ", пустых " + hunt.path("empty").asInt());
        }
        JsonNode route = r.path("route");
        if (route.isObject()) {
            lines.add("🛣 маршрут «" + route.path("name").asText("") + "»: точка " + route.path("i").asInt() + " из " + route.path("n").asInt());
        }
        if (r.path("jsVer").isTextual()) {
            lines.add("🔖 версия JS " + r.path("jsVer").asText());
        }
        JsonNode warn = r.path("warn");
        if (warn.isArray() && warn.size() > 0) {
            lines.add("⚠ " + String.join(" | ", texts(warn)));
        }
        return String.join("\n", lines);
    }

    /** Результат инструмента ({@code {"result": "..."}} и др.) → текст для чата. */
    public static String toolResult(String json) {
        if (json == null || json.isBlank()) return "готово";
        try {
            JsonNode n = JSON.readTree(json);
            if (n.isObject() && n.has("result") && n.size() == 1) return n.path("result").asText();
            if (n.isObject() && n.has("result")) {
                List<String> parts = new ArrayList<>();
                parts.add(n.path("result").asText());
                n.properties().forEach(e -> {
                    if (!"result".equals(e.getKey())) parts.add(e.getKey() + ": " + brief(e.getValue()));
                });
                return String.join("\n", parts);
            }
            return cut(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n), 3500);
        } catch (RuntimeException e) {
            return cut(json, 3500);
        }
    }

    /** Смерть: бот/игрок, место, HP до смерти. */
    public static String death(Map<String, Object> ev) {
        String who = "bot".equals(String.valueOf(ev.get("source"))) ? "бота" : "ваша";
        StringBuilder sb = new StringBuilder("💀 Персонаж погиб (" + who + " игра)");
        if (ev.get("zone") != null) sb.append(" · зона ").append(ev.get("zone"));
        if (ev.get("x") instanceof Number x && ev.get("y") instanceof Number y) sb.append(" (").append(round(x)).append("; ").append(round(y)).append(")");
        if (ev.get("hpBefore") instanceof Number hp) sb.append("\nHP перед смертью: ").append(Math.round(hp.doubleValue() * 100)).append("%");
        return sb.toString();
    }

    /** Конец задачи бота. Короткие служебные задачи не интересны — возвращает null. */
    public static String jobEnd(Map<String, Object> ev) {
        String name = String.valueOf(ev.get("name"));
        if (!List.of("grind", "hunt", "goRoute", "sellAt", "revive", "goRoad").contains(name)) return null;
        String result = ev.get("result") == null ? "" : String.valueOf(ev.get("result"));
        String status = String.valueOf(ev.get("status"));
        String icon = switch (status) {
            case "dead" -> "💀";
            case "paused", "stopped", "failed" -> "⏸";
            case "error" -> "❗";
            default -> "✅";
        };
        StringBuilder sb = new StringBuilder(icon + " " + title(name) + " завершён");
        if (!result.isBlank()) sb.append(": ").append(result);
        if (ev.get("stats") instanceof Map<?, ?> st && st.get("kills") != null) {
            sb.append("\n🗡 убито ").append(st.get("kills")).append(" · добыча ").append(st.get("loots") == null ? 0 : st.get("loots"));
        }
        return sb.toString();
    }

    /** Продажа. */
    public static String sell(Map<String, Object> ev) {
        Object sold = ev.get("sold"), money = ev.get("moneyDelta");
        boolean ok = !Boolean.FALSE.equals(ev.get("ok"));
        return (ok ? "💰 Продано предметов: " : "⚠ Продажа не завершена, продано: ") + (sold == null ? 0 : sold)
                + (money instanceof Number m ? " · выручка " + coins(m.longValue()) : "");
    }

    static String title(String job) {
        return switch (job) {
            case "grind" -> "Гринд";
            case "hunt" -> "Охота";
            case "goRoute" -> "Маршрут";
            case "sellAt" -> "Продажа";
            case "revive" -> "Воскрешение";
            case "goRoad" -> "Дорога";
            default -> job;
        };
    }

    /** Медь → «1з 23с 45м». */
    /** Медь → «12з 3с 50м»; нулевые части пропускаются, 0 → «0м». */
    public static String coins(long copper) {
        long g = copper / 10000, s = copper / 100 % 100, c = copper % 100;
        StringBuilder sb = new StringBuilder();
        if (g != 0) sb.append(g).append("з ");
        if (s != 0) sb.append(s).append("с ");
        if (c != 0 || sb.length() == 0) sb.append(c).append("м");
        return sb.toString().trim();
    }

    private static String num(JsonNode n) {
        return String.format(java.util.Locale.ROOT, "%.1f", n.asDouble());
    }

    private static String round(Number n) {
        return String.format(java.util.Locale.ROOT, "%.1f", n.doubleValue());
    }

    private static String compact(JsonNode obj) {
        List<String> parts = new ArrayList<>();
        obj.properties().forEach(e -> parts.add(e.getKey() + " " + e.getValue().asText()));
        return String.join(", ", parts);
    }

    private static String brief(JsonNode n) {
        return n.isValueNode() ? n.asText() : cut(n.toString(), 300);
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(cut(n.asText(), 120));
        return out;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
