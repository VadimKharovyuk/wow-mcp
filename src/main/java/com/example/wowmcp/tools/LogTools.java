package com.example.wowmcp.tools;

import com.example.wowmcp.db.BotLogRepository;
import com.example.wowmcp.game.GameBridge;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.jsonResult;

/** Чтение журнала бота (таблица bot_log, 10 дней): ошибки и события боя по профилю. */
@Component
public class LogTools {

    static final int DEFAULT_MINUTES = 120;
    static final int DEFAULT_LIMIT = 15;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

    private final BotLogRepository logs;
    private final GameBridge bridge;

    public LogTools(BotLogRepository logs, GameBridge bridge) {
        this.logs = logs;
        this.bridge = bridge;
    }

    /** Разобранный запрос: профиль (null = текущий), категория (null = любая), период и число записей. */
    record Query(String profile, String category, int minutes, int limit) {
    }

    private static final Map<String, String> CATEGORY_WORDS = Map.ofEntries(
            Map.entry("нажатия", "press_fail"), Map.entry("нажатие", "press_fail"), Map.entry("клавиши", "press_fail"),
            Map.entry("лечение", "heal"), Map.entry("хил", "heal"),
            Map.entry("бой", "fight"), Map.entry("бои", "fight"),
            Map.entry("подход", "approach"), Map.entry("лут", "loot"),
            Map.entry("баффы", "buff"), Map.entry("бафы", "buff"),
            Map.entry("смерти", "death"), Map.entry("смерть", "death"),
            Map.entry("задачи", "job"), Map.entry("задача", "job"),
            Map.entry("торговец", "vendor"), Map.entry("продажа", "vendor"),
            Map.entry("сбои", "error"));
    private static final Map<String, String> PROFILE_WORDS = Map.of(
            "паладин", "paladin", "paladin", "paladin", "охотник", "hunter", "hunter", "hunter", "все", "*");

    /** «2 часа лут паладин» → период, категория, профиль. Слова в любом порядке; незнакомые игнорируются. */
    static Query parse(String arg) {
        String profile = null, category = null;
        int minutes = DEFAULT_MINUTES;
        if (arg != null) {
            String[] t = arg.toLowerCase(Locale.ROOT).strip().split("\\s+");
            for (int i = 0; i < t.length; i++) {
                String w = t[i];
                if (w.isEmpty()) continue;
                if (w.matches("\\d+") && i + 1 < t.length) {
                    int n = Integer.parseInt(w);
                    String u = t[i + 1];
                    if (u.startsWith("мин")) { minutes = n; i++; continue; }
                    if (u.startsWith("час") || u.equals("ч")) { minutes = n * 60; i++; continue; }
                    if (u.startsWith("дн") || u.startsWith("сут")) { minutes = n * 1440; i++; continue; }
                }
                if (CATEGORY_WORDS.containsKey(w)) category = CATEGORY_WORDS.get(w);
                else if (PROFILE_WORDS.containsKey(w)) profile = PROFILE_WORDS.get(w);
            }
        }
        int max = BotLogRepository.KEEP_DAYS * 1440;
        return new Query(profile, category, Math.max(1, Math.min(minutes, max)), DEFAULT_LIMIT);
    }

    @Tool(name = "logs", description = """
            Журнал бота за последние минуты/часы: ошибки (warn, error) или все события, по профилю (paladin / hunter) \
            и категории (press_fail, heal, fight, approach, loot, buff, death, job, vendor, error). \
            Хранится 10 дней. Без профиля берётся текущий профиль бота.""")
    public String logs(
            @ToolParam(description = "true — только предупреждения и ошибки; false — все события", required = false) Boolean errorsOnly,
            @ToolParam(description = "Запрос словами: «2 часа», «30 минут», «лут», «паладин», «все»; можно пусто", required = false) String query) {
        return jsonResult(render(errorsOnly == null || errorsOnly, query));
    }

    /** Текст ответа: сводка по категориям и последние записи. Вызывается и из Telegram-команд «ошибки» / «логи». */
    public String render(boolean errorsOnly, String arg) {
        if (!logs.isEnabled()) return "База данных выключена — журнала нет.";
        Query q = parse(arg);
        String profile = q.profile() == null ? currentProfile() : "*".equals(q.profile()) ? null : q.profile();
        long since = System.currentTimeMillis() - q.minutes() * 60_000L;
        List<String> levels = errorsOnly ? List.of("warn", "error") : List.of("info", "warn", "error");
        List<Map<String, Object>> rows = logs.recent(profile, q.category(), levels, since, q.limit());
        String head = (errorsOnly ? "Ошибки" : "Журнал") + " · " + (profile == null ? "все профили" : profile)
                + " · за " + (q.minutes() % 60 == 0 && q.minutes() >= 120 ? q.minutes() / 60 + " ч" : q.minutes() + " мин")
                + (q.category() == null ? "" : " · " + q.category());
        if (rows.isEmpty()) return head + "\nЗаписей нет.";
        StringBuilder sb = new StringBuilder(head).append('\n');
        List<Map<String, Object>> sum = logs.summary(profile, since);
        if (!sum.isEmpty()) {
            sb.append("Сводка: ");
            int k = 0;
            for (Map<String, Object> r : sum) {
                if (errorsOnly && "info".equals(r.get("level"))) continue;
                if (k++ > 0) sb.append(", ");
                sb.append(r.get("category")).append(' ').append(r.get("n"));
            }
            sb.append('\n');
        }
        for (Map<String, Object> r : rows) {
            Object ts = r.get("ts");
            String time = ts instanceof java.sql.Timestamp t ? TIME.format(t.toInstant().atZone(ZoneId.systemDefault())) : String.valueOf(ts);
            String mark = "error".equals(r.get("level")) ? "🔴" : "warn".equals(r.get("level")) ? "🟡" : "▫";
            sb.append(mark).append(' ').append(time).append(" [").append(r.get("category")).append("] ")
                    .append(r.get("message")).append('\n');
        }
        return sb.toString().strip();
    }

    private String currentProfile() {
        try {
            Object p = bridge.eval("() => bot.profile || null");
            return p == null || "null".equals(String.valueOf(p)) ? null : String.valueOf(p);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
