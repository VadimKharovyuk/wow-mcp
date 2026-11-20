package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.EventCollector;
import com.example.wowmcp.db.LeaderLock;
import com.example.wowmcp.game.GameBridge;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.jsonResult;

/**
 * Самопроверка: роль процесса (лидер или нет), база, загружен ли бот, совпадают ли версии JS/Lua в игре с диском/jar,
 * идёт ли сбор событий. Ничего не меняет и не подключается к Chrome — безопасно вызывать в любой момент.
 */
@Component
public class HealthTools {

    private final GameBridge bridge;
    private final Database db;
    private final LeaderLock leader;
    private final EventCollector collector;

    public HealthTools(GameBridge bridge, Database db, LeaderLock leader, EventCollector collector) {
        this.bridge = bridge;
        this.db = db;
        this.leader = leader;
        this.collector = collector;
    }

    /** Снимок состояния для диагностики. Возраст «-1» — этого ещё не было. */
    public record Snapshot(boolean leader, boolean dbEnabled, boolean botLoaded, boolean busy,
                    String jsDisk, String jsPage, String luaJava, String luaPage,
                    long drainAgeSec, long eventAgeSec, Boolean luaDataOk) {
        /** Без признака «данные Lua читаются» (неизвестно — не считается проблемой). */
        public Snapshot(boolean leader, boolean dbEnabled, boolean botLoaded, boolean busy,
                        String jsDisk, String jsPage, String luaJava, String luaPage,
                        long drainAgeSec, long eventAgeSec) {
            this(leader, dbEnabled, botLoaded, busy, jsDisk, jsPage, luaJava, luaPage, drainAgeSec, eventAgeSec, null);
        }
    }

    @Tool(name = "health", description = """
            Самопроверка: лидер ли этот процесс, включена ли база, загружен ли бот в игру, совпадают ли версии JS и Lua \
            в игре с файлами, идёт ли сбор событий. Ничего не меняет. Используй, если журнал пуст или бот ведёт себя странно.""")
    public String health() {
        return jsonResult(render());
    }

    /** Текст самопроверки; вызывается и из Telegram-команды «здоровье». */
    public String render() {
        Snapshot s = snapshot();
        List<String> problems = problems(s);
        StringBuilder sb = new StringBuilder("🩺 Самопроверка\n");
        sb.append("Процесс: ").append(s.leader() ? "лидер (читает события игры)" : "не лидер (события читает другой процесс)").append('\n');
        sb.append("База: ").append(s.dbEnabled() ? "включена" : "ВЫКЛЮЧЕНА").append('\n');
        sb.append("Бот в игре: ").append(s.botLoaded() ? "загружен" : "не загружен").append(s.busy() ? " (страница занята задачей)" : "").append('\n');
        sb.append("JS: игра ").append(nv(s.jsPage())).append(" · диск ").append(nv(s.jsDisk())).append('\n');
        sb.append("Lua: игра ").append(nv(s.luaPage())).append(" · jar ").append(nv(s.luaJava())).append('\n');
        if (s.luaDataOk() != null) sb.append("Данные Lua: ").append(s.luaDataOk() ? "читаются" : "НЕ читаются").append('\n');
        if (s.leader()) {
            sb.append("События: последний опрос ").append(ago(s.drainAgeSec())).append(", последнее событие ").append(ago(s.eventAgeSec())).append('\n');
        }
        sb.append(problems.isEmpty() ? "✅ Проблем не видно" : String.join("\n", problems));
        return sb.toString();
    }

    public Snapshot snapshot() {
        Map<String, Object> h = bridge.health();
        long now = System.currentTimeMillis();
        return new Snapshot(leader.isLeader(), db.isEnabled(), Boolean.TRUE.equals(h.get("botLoaded")), Boolean.TRUE.equals(h.get("busy")),
                str(h.get("jsVerDisk")), str(h.get("jsVerPage")), str(h.get("luaVerJava")), str(h.get("luaVerPage")),
                age(collector.lastDrainAt(), now), age(collector.lastEventAt(), now),
                h.get("luaDataOk") instanceof Boolean ok ? ok : null);
    }

    /** Что не так, простыми словами; пусто — всё в порядке. Чистая функция (удобно тестировать). */
    public static List<String> problems(Snapshot s) {
        List<String> p = new ArrayList<>();
        if (!s.dbEnabled()) {
            p.add("❗ База выключена: журнал, места, маршруты и зоны недоступны. Перезапусти процесс (./deploy.sh).");
        }
        if (!s.botLoaded()) {
            p.add("⚠ Бот не загружен в странице игры: скажи «перезагрузи бота». Если не помогло — окно Chrome с игрой свёрнуто или игра на паузе.");
        } else {
            if (s.jsPage() == null) {
                p.add("⚠ В игре старый загрузчик JS (версия не определена): скажи «перезагрузи бота».");
            } else if (s.jsDisk() != null && !s.jsPage().equals(s.jsDisk())) {
                p.add("⚠ JS в игре (" + s.jsPage() + ") не совпадает с файлами (" + s.jsDisk() + "): скажи «перезагрузи бота».");
            }
            String lp = normNum(s.luaPage()), lj = normNum(s.luaJava());
            if (lp != null && lj != null && !lp.equals(lj)) {
                p.add("⚠ Lua в игре (" + lp + ") не совпадает с jar (" + lj + "): запусти ./deploy.sh и скажи «перезагрузи бота».");
            }
        }
        if (s.botLoaded() && Boolean.FALSE.equals(s.luaDataOk())) {
            p.add("❗ Данные из игры неполные (уровень игрока, зона, мана, дистанция до цели не читаются): бот видит ману 0 и «слепо» отдыхает/не баффает. "
                    + "Перезагрузи страницу игры (F5), затем скажи «перезагрузи бота».");
        }
        if (s.leader() && s.botLoaded() && !s.busy() && s.drainAgeSec() >= 0 && s.drainAgeSec() > 120) {
            p.add("⚠ Сбор событий не опрашивал страницу " + s.drainAgeSec() + " с: журнал и статистика не пишутся.");
        }
        if (s.leader() && s.botLoaded() && s.drainAgeSec() < 0) {
            p.add("⚠ Сбор событий ещё ни разу не опросил страницу после запуска процесса.");
        }
        return p;
    }

    private static String normNum(String v) {
        if (v == null) return null;
        try {
            return Long.toString((long) Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return v.trim();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nv(String v) {
        return v == null ? "?" : v;
    }

    private static long age(long at, long now) {
        return at <= 0 ? -1 : Math.max(0, (now - at) / 1000);
    }

    private static String ago(long sec) {
        if (sec < 0) return "ещё не было";
        if (sec < 120) return sec + " с назад";
        if (sec < 7200) return sec / 60 + " мин назад";
        return sec / 3600 + " ч назад";
    }
}
