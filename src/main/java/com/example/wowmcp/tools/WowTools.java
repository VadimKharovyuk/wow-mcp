package com.example.wowmcp.tools;

import com.example.wowmcp.db.DbService;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import com.example.wowmcp.learn.RotationLearner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MCP-инструменты. Все возвращают короткую JSON-строку.
 *
 * <p>Java только вызывает функции bot.js; циклы ходьбы, боя и сбора добычи остаются в браузере.
 */
@Component
public class WowTools {

    private static final Logger log = LoggerFactory.getLogger(WowTools.class);

    private static final int WAIT_JOB_DEFAULT_SECONDS = 30;
    private static final int WAIT_JOB_MAX_SECONDS = 60;

    /** Значения по умолчанию для фильтра уровня цели в подборе точек. */
    private static final int DEFAULT_MIN_LVL_DIFF = -5;
    private static final int DEFAULT_MAX_LVL_DIFF = 2;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final GameBridge bridge;
    private final DbService db;
    private final RotationLearner learner;

    private volatile String lastRecordSession;
    private volatile java.time.Instant lastRecordStart;

    public WowTools(GameBridge bridge, DbService db, RotationLearner learner) {
        this.bridge = bridge;
        this.db = db;
        this.learner = learner;
    }

    @Tool(name = "get_state", description = """
            Показывает состояние персонажа: координаты x/y в зоне (0–100), процент HP, \
            идёт ли бой, есть ли цель и какая задача сейчас выполняется. Поле ready — маска \
            готовых умений панели 1–9, moDead — под курсором труп, lootN — предметов в окне добычи, \
            tLvl/tFlags/tDist — уровень/флаги/дистанция цели, manaPct — мана, grind — статистика гринда. \
            Если вернулась ошибка "не в мире" — не повторять вызов, сообщить игроку.""")
    public String getState() {
        return bridge.report();
    }

    @Tool(name = "spells", description = """
            Показывает заклинания на панелях: spellId, nameHash, слот, привязанная клавиша, \
            готовность и наличие дальности. Название можно узнать в игре через \
            /run print(GetSpellInfo(<spellId>)).""")
    public String spells() {
        bridge.ensureReady();
        Object raw = bridge.eval("() => JSON.stringify(bot.spells())");
        try {
            return JSON.writeValueAsString(Map.of(
                    "spells", JSON.readValue(String.valueOf(raw), Object.class)));
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of("spells", raw));
        }
    }

    @Tool(name = "go_to", description = """
            Бежать к точке (x, y в координатах зоны 0–100), по пути отбиваясь от мобов, \
            а после боя собирая добычу (autoLoot). Запускается в фоне и сразу возвращает \
            ответ — результат потом смотри через wait_job.""")
    public String goTo(
            @ToolParam(description = "Координата X в зоне, от 0 до 100") double x,
            @ToolParam(description = "Координата Y в зоне, от 0 до 100") double y,
            @ToolParam(required = false, description = "Собирать добычу после боя, по умолчанию true")
            Boolean autoLoot) {
        validateCoordinates(x, y);
        boolean loot = autoLoot == null || autoLoot;
        return jsonResult(bridge.start("go", List.of(x, y, Map.of("autoLoot", loot))));
    }

    @Tool(name = "move_to", description = """
            Бежать к точке (x, y в координатах зоны 0–100) без боя. \
            Останавливается, если напали мобы. Работает в фоне — результат смотри через wait_job.""")
    public String moveTo(
            @ToolParam(description = "Координата X в зоне, от 0 до 100") double x,
            @ToolParam(description = "Координата Y в зоне, от 0 до 100") double y) {
        validateCoordinates(x, y);
        return jsonResult(bridge.start("moveTo", List.of(x, y)));
    }

    @Tool(name = "fight", description = """
            Драться с текущими врагами: умения жмутся по реальной готовности (маска ready), \
            с реакцией на ошибки «не лицом к цели» и «слишком далеко». \
            Работает в фоне — результат смотри через wait_job.""")
    public String fight() {
        return jsonResult(bridge.start("fight", List.of()));
    }

    @Tool(name = "loot", description = """
            Найти ближайший труп на экране и собрать добычу. Вызывать после боя. \
            Работает в фоне — результат смотри через wait_job.""")
    public String loot() {
        return jsonResult(bridge.start("loot", List.of()));
    }

    @Tool(name = "rest", description = """
            Отдыхать до восстановления 80% HP. Работает в фоне — результат смотри через wait_job.""")
    public String rest() {
        return jsonResult(bridge.start("rest", List.of()));
    }

    @Tool(name = "grind", description = """
            Автоматическая охота вокруг текущей точки: найти подходящего моба, подойти, \
            убить, собрать добычу (loot), отдохнуть, повторить. Работает в фоне. Возвращается по \
            лимиту убийств/времени или при проблеме (смерть, застрял, сумки полные, мобов нет). \
            Во время охоты вызывай wait_job; статистика — в поле grind. \
            loot — собирать добычу после боя (true/false, по умолчанию false).

            Если в базе есть места прошедших убийств в этой зоне — бот ходит по ним (быстрее), \
            иначе ищет вокруг. Параметр radius ограничивает зону охоты вокруг точки старта. \
            respawnSec — через сколько секунд после визита место снова считается «тёплым».
            mode — режим обхода мест: 'radius' (по умолчанию, охота вокруг старта) или
            'patrol' (кольцевой обход мест зоны в пределах maxDistance, 2..40, по умолчанию 15).
            minFreeSlots — при таком/меньшем числе свободных слотов идти к ближайшему торговцу зоны
            (работает при любом loot); если торговец не известен — гринд остановится.""")
    public String grind(
            @ToolParam(required = false, description = "Радиус охоты вокруг старта, 0.5..10 (по умолчанию 2.5)")
            Double radius,
            @ToolParam(required = false, description = "Лимит убийств, 0 = без лимита (по умолчанию 10)")
            Integer maxKills,
            @ToolParam(required = false, description = "Лимит времени в минутах, 1..180 (по умолчанию 30)")
            Integer maxMinutes,
            @ToolParam(required = false, description = "Минимальная разница уровней цели, -10..5 (по умолчанию -5)")
            Integer minLvlDiff,
            @ToolParam(required = false, description = "Максимальная разница уровней цели, -10..5 (по умолчанию 2)")
            Integer maxLvlDiff,
            @ToolParam(required = false, description = "Порог маны для питья, 0..1 (по умолчанию 0.3)")
            Double manaBelow,
            @ToolParam(required = false, description = "Слот лечения в отдыхе, '0'..'9' (по умолчанию '4')")
            String healKey,
            @ToolParam(required = false, description = "Слот питья в отдыхе, '0'..'9' (по умолчанию '0')")
            String drinkKey,
            @ToolParam(required = false, description = "Использовать базу тёплых точек охоты, true/false (по умолчанию true)")
            Boolean useSpots,
            @ToolParam(required = false, description = "Собирать добычу после боя, true/false (по умолчанию false)")
            Boolean loot,
            @ToolParam(required = false, description = "Через сколько секунд место снова считается тёплым, 60..1800 (по умолчанию 240)")
            Integer respawnSec,
            @ToolParam(required = false, description = "При таком/меньшем числе свободных слотов идти к торговцу, 0..10 (по умолчанию 2)")
            Integer minFreeSlots,
            @ToolParam(required = false, description = "Режим обхода мест: 'radius' (по умолчанию) или 'patrol'")
            String mode,
            @ToolParam(required = false, description = "Максимальное расстояние до мест маршрута в patrol, 2..40 (по умолчанию 15)")
            Double maxDistance) {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (radius != null) {
            if (!Double.isFinite(radius) || radius < 0.5 || radius > 10) {
                throw new IllegalArgumentException("radius должен быть от 0.5 до 10");
            }
            opts.put("radius", radius);
        }
        if (maxKills != null) {
            if (maxKills < 0 || maxKills > 200) {
                throw new IllegalArgumentException("maxKills должен быть от 0 до 200");
            }
            opts.put("maxKills", maxKills);
        }
        if (maxMinutes != null) {
            if (maxMinutes < 1 || maxMinutes > 180) {
                throw new IllegalArgumentException("maxMinutes должен быть от 1 до 180");
            }
            opts.put("maxMinutes", maxMinutes);
        }
        if (minLvlDiff != null) {
            if (minLvlDiff < -10 || minLvlDiff > 5) {
                throw new IllegalArgumentException("minLvlDiff должен быть от -10 до 5");
            }
            opts.put("minLvlDiff", minLvlDiff);
        }
        if (maxLvlDiff != null) {
            if (maxLvlDiff < -10 || maxLvlDiff > 5) {
                throw new IllegalArgumentException("maxLvlDiff должен быть от -10 до 5");
            }
            opts.put("maxLvlDiff", maxLvlDiff);
        }
        if (manaBelow != null) {
            if (!Double.isFinite(manaBelow) || manaBelow < 0 || manaBelow > 1) {
                throw new IllegalArgumentException("manaBelow должен быть от 0 до 1");
            }
            opts.put("manaBelow", manaBelow);
        }
        if (healKey != null) {
            if (!healKey.matches("[0-9]")) {
                throw new IllegalArgumentException("healKey должен быть слотом '0'..'9'");
            }
            opts.put("healKey", healKey);
        }
        if (drinkKey != null) {
            if (!drinkKey.matches("[0-9]")) {
                throw new IllegalArgumentException("drinkKey должен быть слотом '0'..'9'");
            }
            opts.put("drinkKey", drinkKey);
        }
        if (respawnSec != null) {
            if (respawnSec < 60 || respawnSec > 1800) {
                throw new IllegalArgumentException("respawnSec должен быть от 60 до 1800");
            }
            opts.put("respawnSec", respawnSec);
        }
        if (loot != null) {
            opts.put("loot", loot);
        }
        if (minFreeSlots != null) {
            if (minFreeSlots < 0 || minFreeSlots > 10) {
                throw new IllegalArgumentException("minFreeSlots должен быть от 0 до 10");
            }
            opts.put("minFreeSlots", minFreeSlots);
        }
        String modeNorm = null;
        if (mode != null) {
            modeNorm = mode.trim().toLowerCase(Locale.ROOT);
            if (!modeNorm.equals("radius") && !modeNorm.equals("patrol")) {
                throw new IllegalArgumentException("mode должен быть 'radius' или 'patrol'");
            }
            opts.put("mode", modeNorm);
        }
        if (maxDistance != null) {
            if (!Double.isFinite(maxDistance) || maxDistance < 2 || maxDistance > 40) {
                throw new IllegalArgumentException("maxDistance должен быть от 2 до 40");
            }
            opts.put("maxDistance", maxDistance);
        }
        boolean patrol = "patrol".equals(modeNorm);

        // Тёплые точки охоты из БД (задача 4б): только если БД доступна и useSpots не false.
        // В patrol берём все места зоны без фильтра по радиусу (фильтр по уровню моба — тот же),
        // маршрут и ограничение maxDistance строит 40-grind.js.
        List<Map<String, Object>> spots = List.of();
        Map<String, Object> vendorOpt = null;
        if ((useSpots == null || useSpots) || db.isEnabled()) {
            if (db.isEnabled()) {
                GameState state = GameState.parse(bridge.report());
                // vendor for this zone (nearest) — передаём всегда при наличии в БД
                Map<String, Object> v = db.nearestVendor(state.zone(), state.x(), state.y());
                if (v != null) {
                    vendorOpt = vendorArg(v);
                    opts.put("vendor", vendorOpt);
                }
                if (useSpots == null || useSpots || patrol) {
                    int minLvl = state.pLvl() + (minLvlDiff == null ? DEFAULT_MIN_LVL_DIFF : minLvlDiff);
                    int maxLvl = state.pLvl() + (maxLvlDiff == null ? DEFAULT_MAX_LVL_DIFF : maxLvlDiff);
                    spots = db.querySpots(state.zone(), minLvl, maxLvl);
                    if (!spots.isEmpty()) {
                        // Playwright принимает в page.evaluate только Integer/Double/String/Boolean/Map/List:
                        // Long (count) и BigDecimal (round(avg(...))) из БД валидны для JDBC, но не для Playwright.
                        // Приводим каждое место к { x: double, y: double, kills: int, lvl: int|null }.
                        List<Map<String, Object>> cleanSpots = new ArrayList<>();
                        for (Map<String, Object> p : spots) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("x", ((Number) p.get("x")).doubleValue());
                            s.put("y", ((Number) p.get("y")).doubleValue());
                            s.put("kills", ((Number) p.get("kills")).intValue());
                            Object lvl = p.get("lvl");
                            s.put("lvl", lvl == null ? null : ((Number) lvl).intValue());
                            cleanSpots.add(s);
                        }
                        opts.put("spots", cleanSpots);
                    }
                }
            }
        }
        List<Object> args = opts.isEmpty() ? List.of() : List.of(opts);
        String startResult = bridge.start("grind", args);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", startResult);
        response.put("mode", patrol ? "patrol" : "radius");
        response.put("spots", spots.size());
        response.put("vendor", vendorOpt != null);
        return JSON.writeValueAsString(response);
    }

    @Tool(name = "look_around", description = """
            Найти цель сканом экрана мышью (вся видимая область, не только впереди). \
            Работает в фоне — результат смотри через wait_job.""")
    public String lookAround() {
        return jsonResult(bridge.start("lookAround", List.of()));
    }

    @Tool(name = "record_start", description = """
            Включить режим обучения: бот пишет путь и убийства в БД, пока играет человек. \
            Возвращает sessionId для record_stop.""")
    public String recordStart() {
        bridge.ensureReady();
        String sessionId = UUID.randomUUID().toString();
        bridge.eval("(s) => { bot.recording = true; bot.recSession = s; return bot.recSession; }", sessionId);
        lastRecordSession = sessionId;
        lastRecordStart = java.time.Instant.now();
        return JSON.writeValueAsString(Map.of("result", "запись начата", "sessionId", sessionId));
    }

    @Tool(name = "record_stop", description = """
            Остановить запись. Возвращает сводку сессии: точек пути и убийств (из БД, если настроена).""")
    public String recordStop() {
        bridge.ensureReady();
        bridge.eval("() => { bot.recording = false; return 'ok'; }");
        String sid = lastRecordSession;
        long pathPoints = db.isEnabled() ? db.countPathPoints(sid) : 0;
        long kills = db.isEnabled() && lastRecordStart != null ? db.countKillsSince(lastRecordStart) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "запись остановлена");
        result.put("sessionId", sid);
        result.put("pathPoints", pathPoints);
        result.put("kills", kills);
        result.put("dbEnabled", db.isEnabled());
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "spots", description = """
            Что бот знает о текущей зоне: убийства сгруппированы по сетке 1×1 единица карты \
            (центр, количество, средний уровень моба, последнее убийство). Только места с >= 2 \
            убийствами за 30 дней. Топ-30 по количеству. Без БД — сообщение «база не настроена».""")
    public String spots(
            @ToolParam(required = false, description = "Минимальная разница уровней цели, -10..5 (по умолчанию -5)")
            Integer minLvlDiff,
            @ToolParam(required = false, description = "Максимальная разница уровней цели, -10..5 (по умолчанию 2)")
            Integer maxLvlDiff) {
        bridge.ensureReady();
        GameState state = GameState.parse(bridge.report());
        int zone = state.zone();
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", zone));
        }
        int diffMin = minLvlDiff == null ? DEFAULT_MIN_LVL_DIFF : minLvlDiff;
        int diffMax = maxLvlDiff == null ? DEFAULT_MAX_LVL_DIFF : maxLvlDiff;
        List<Map<String, Object>> rows = db.querySpots(zone, state.pLvl() + diffMin, state.pLvl() + diffMax);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", zone);
        result.put("spots", rows);
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "wait_job", description = """
            Ждёт завершения текущей задачи бота (go_to/move_to/fight/loot/rest/grind), опрашивая \
            состояние раз в секунду, и возвращает финальный отчёт. По умолчанию ждёт 30 секунд, максимум 60.""")
    public String waitJob(
            @ToolParam(required = false, description = "Сколько секунд ждать, по умолчанию 30, максимум 60")
            Integer maxSeconds) {
        int seconds = normalizeWaitSeconds(maxSeconds);

        // Первый отчёт заодно проверяет готовность бота (bot.js + Lua).
        String report = bridge.report();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (isRunning(report) && System.nanoTime() < deadline) {
            sleepOneSecond();
            report = bridge.reportRaw();
        }
        return report;
    }

    @Tool(name = "stop", description = """
            Экстренно останавливает бота: отпускает клавиши и прекращает текущую задачу.""")
    public String stop() {
        return jsonResult(bridge.stop());
    }

    @Tool(name = "set_rotation", description = """
            Задать приоритет умений: список от самого важного к основному удару. \
            Поддерживаются v1 (по слоту панели, key "1".."9") и v2 (по nameHash заклинания, spell). \
            Формат: JSON-массив [{"key":"2","maxHpPct":0.35},{"key":"4","waitAfter":{"key":"2","ms":1000}}] \
            или [{"spell":123,"maxHpPct":0.35},{"spell":456,"waitAfter":{"spell":123,"ms":1000}}]. \
            maxHpPct необязателен (0..1): умение применяется, только если HP ниже этой доли. \
            outOfCombatOnly (true/false) — умение только вне боя. \
            waitAfter {key|spell, ms} — не переходить к следующим умениям, пока с нажатия прошло меньше ms.""")
    public String setRotation(
            @ToolParam(description = "JSON-массив вида [{\"key\":\"2\",\"maxHpPct\":0.35},"
                    + "{\"key\":\"4\",\"maxHpPct\":0.35,\"waitAfter\":{\"key\":\"2\",\"ms\":1000}},"
                    + "{\"key\":\"3\",\"outOfCombatOnly\":true},{\"key\":\"1\"}] или "
                    + "[{\"spell\":123,\"maxHpPct\":0.35},{\"spell\":456,\"waitAfter\":{\"spell\":123,\"ms\":1000}}]")
            String rotationJson) {
        List<Map<String, Object>> rotation = parseRotation(rotationJson);
        bridge.ensureReady();
        Object saved = bridge.eval(
                "(r) => { bot.rotation = r; return JSON.stringify(bot.rotation); }", rotation);
        return JSON.writeValueAsString(Map.of(
                "result", "ротация сохранена",
                "rotation", JSON.readValue(String.valueOf(saved), Object.class)));
    }

    @Tool(name = "get_rotation", description = """
            Показывает текущий приоритет умений. Для v2 (spell) дополнительно показывает spellId (если известен), \
            текущую клавишу из spells() (например "O", "2", "Shift+E") и правило; а также статистику обучения.""")
    public String getRotation() {
        bridge.ensureReady();
        Object rotation = bridge.eval("() => JSON.stringify(bot.rotation)");
        String profile = null;
        try {
            profile = String.valueOf(bridge.eval("() => bot.profile"));
        } catch (RuntimeException ignored) {
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> rotationList;
        try {
            rotationList = JSON.readValue(String.valueOf(rotation), new tools.jackson.core.type.TypeReference<>() {});
        } catch (RuntimeException e) {
            result.put("rotation", rotation);
            rotationList = null;
        }
        if (rotationList != null) {
            List<Object> spellList;
            try {
                Object rawSpells = bridge.eval("() => JSON.stringify(bot.spells())");
                spellList = JSON.readValue(String.valueOf(rawSpells), new tools.jackson.core.type.TypeReference<>() {});
            } catch (RuntimeException e) {
                spellList = List.of();
            }
            Map<Integer, Map<String, Object>> byHash = new LinkedHashMap<>();
            for (Object o : spellList) {
                if (o instanceof Map<?, ?> m && m.get("nameHash") instanceof Number n) {
                    byHash.put(n.intValue(), (Map<String, Object>) m);
                }
            }
            for (int idx = 0; idx < rotationList.size(); idx++) {
                Object o = rotationList.get(idx);
                if (!(o instanceof Map<?, ?> rule)) continue;
                Object spellValue = rule.get("spell");
                if (spellValue instanceof Number n) {
                    Map<String, Object> info = byHash.get(n.intValue());
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) rule);
                    if (info != null) {
                        if (info.get("spellId") != null) copy.put("spellId", info.get("spellId"));
                        copy.put("slot", info.get("slot"));
                        copy.put("keyCode", info.get("keyCode"));
                        copy.put("mods", info.get("mods"));
                        copy.put("key", formatSpellKey(info));
                    }
                    rotationList.set(idx, copy);
                }
            }
        }
        result.put("rotation", rotationList);
        result.put("profile", "null".equals(profile) ? null : profile);
        if (db.isEnabled() && profile != null && !"null".equals(profile)) {
            Map<String, Object> row = db.loadRotationProfile(profile);
            if (row != null && row.get("stats_json") != null) {
                try {
                    result.put("stats", JSON.readValue(String.valueOf(row.get("stats_json")), Object.class));
                } catch (RuntimeException e) {
                    result.put("stats", row.get("stats_json"));
                }
            } else {
                result.put("stats", null);
            }
            result.put("samples", row == null ? null : row.get("samples"));
        } else {
            result.put("stats", null);
            result.put("samples", null);
        }
        return JSON.writeValueAsString(result);
    }

    private static String formatSpellKey(Map<String, Object> info) {
        if (info == null) return null;
        Integer keyCode = asInt(info.get("keyCode"));
        if (keyCode == null || keyCode == 0) return null;
        Integer mods = asInt(info.get("mods"));
        int m = mods == null ? 0 : mods;
        String base = keyChar(keyCode);
        if (base == null || base.isEmpty()) base = "keyCode:" + keyCode;
        StringBuilder sb = new StringBuilder();
        if ((m & 1) != 0) sb.append("Shift+");
        if ((m & 2) != 0) sb.append("Ctrl+");
        if ((m & 4) != 0) sb.append("Alt+");
        sb.append(base);
        return sb.toString();
    }

    private static String keyChar(int c) {
        if (c >= 65 && c <= 90) return String.valueOf((char) c);
        if (c >= 48 && c <= 57) return String.valueOf((char) c);
        if (c == 189) return "-";
        if (c == 187) return "=";
        if (c >= 112 && c <= 123) return "F" + (c - 111);
        if (c == 32) return "Space";
        return null;
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    @Tool(name = "set_profile", description = """
            Задать активный профиль персонажа (имя). Бот начинает записывать нажатия игрока \
            для этого профиля и загружает сохранённую ротацию (если есть). \
            Профиль сохраняется в БД (active_profile) и переживает перезапуск сервера \
            и перезагрузку страницы. \
            Без БД работает в памяти: нажатия не сохраняются, обучение недоступно.""")
    public String setProfile(
            @ToolParam(description = "Имя профиля (например paladin, mage)") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name не должен быть пустым");
        }
        bridge.ensureReady();
        bridge.eval("(p) => bot.setProfile ? bot.setProfile(p) : (bot.profile = p, window.__wowProfile = p)", name);
        Map<String, Object> loaded = null;
        if (db.isEnabled()) {
            db.setActiveProfile(name);   // чтобы autoLoadProfile нашёл профиль после перезапуска
            loaded = db.loadRotationProfile(name);
            if (loaded != null && loaded.get("rotation_json") != null) {
                Object rotation = JSON.readValue(String.valueOf(loaded.get("rotation_json")), Object.class);
                bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", rotation);
            }
            db.touchProfile(name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "профиль установлен: " + name);
        result.put("profile", name);
        result.put("rotationLoaded", loaded != null && loaded.get("rotation_json") != null);
        result.put("dbEnabled", db.isEnabled());
        if (!db.isEnabled()) {
            result.put("note", "база не настроена — нажатия не сохраняются, обучение недоступно");
        }
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "learn_rotation", description = """
            Принудительно переучить ротацию активного профиля. Если есть данные spell_cast — учится только по ним (v2, nameHash); \
            fallback на key_press только когда spell_cast пуст. При недостатке данных spell_cast текущая ротация не меняется.""")
    public String learnRotation() {
        bridge.ensureReady();
        String profile = String.valueOf(bridge.eval("() => bot.profile"));
        if ("null".equals(profile) || profile.isBlank()) {
            return JSON.writeValueAsString(Map.of("result", "профиль не задан — сначала set_profile"));
        }
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — обучение недоступно",
                    "dbEnabled", false));
        }

        // Текущая ротация нужна для сохранения аварийных правил (maxHpPct) при малом числе данных
        List<Map<String, Object>> currentRotation = List.of();
        try {
            Object raw = bridge.eval("() => JSON.stringify(bot.rotation)");
            currentRotation = JSON.readValue(String.valueOf(raw), new tools.jackson.core.type.TypeReference<>() {});
        } catch (RuntimeException ignored) {
        }

        List<Map<String, Object>> spellRows = db.querySpellCasts(profile);
        if (!spellRows.isEmpty()) {
            RotationLearner.SpellLearn attempt = learner.learnSpells(profile, currentRotation);
            if (attempt.isInsufficient()) {
                return JSON.writeValueAsString(Map.of(
                        "result", "мало данных: " + attempt.inCombatCasts() + " применений в бою, нужно " + attempt.requiredInCombat(),
                        "profile", profile,
                        "inCombatCasts", attempt.inCombatCasts(),
                        "requiredInCombat", attempt.requiredInCombat(),
                        "rotationKept", true));
            }
            RotationLearner.Result res = attempt.result();
            if (res == null) {
                return JSON.writeValueAsString(Map.of(
                        "result", "мало данных: " + attempt.inCombatCasts() + " применений в бою, нужно " + attempt.requiredInCombat(),
                        "profile", profile,
                        "inCombatCasts", attempt.inCombatCasts(),
                        "requiredInCombat", attempt.requiredInCombat(),
                        "rotationKept", true));
            }
            String rotJson = JSON.writeValueAsString(res.rotation());
            bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
            String statsJson = res.stats() == null ? null : JSON.writeValueAsString(res.stats());
            db.upsertRotationProfile(profile, rotJson, res.samples(), statsJson);
            learner.setLastLearnedCount(profile, spellRows.size());
            return JSON.writeValueAsString(Map.of("result", "ротация выучена и применена", "profile", profile,
                    "rotation", res.rotation(), "samples", res.samples(), "source", "spell_cast"));
        }

        // Fallback на key_press только если spell_cast пуст
        RotationLearner.Result res = learner.learn(profile);
        if (res == null) {
            return JSON.writeValueAsString(Map.of("result", "недостаточно данных для обучения", "profile", profile));
        }
        String rotJson = JSON.writeValueAsString(res.rotation());
        bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
        String statsJson = res.stats() == null ? null : JSON.writeValueAsString(res.stats());
        db.upsertRotationProfile(profile, rotJson, res.samples(), statsJson);
        learner.setLastLearnedCount(profile, db.queryKeyPresses(profile).size());
        return JSON.writeValueAsString(Map.of("result", "ротация выучена и применена", "profile", profile,
                "rotation", res.rotation(), "samples", res.samples(), "source", "key_press"));
    }

    @Tool(name = "reset_learning", description = """
            УДАЛЯЕТ все нажатия и выученную ротацию профиля (необратимо).""")
    public String resetLearning(
            @ToolParam(description = "Имя профиля") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name не должен быть пустым");
        }
        if (!db.isEnabled()) {
            // В памяти: просто сбросить профиль, если активен
            bridge.ensureReady();
            String active = String.valueOf(bridge.eval("() => bot.profile"));
            if (name.equals(active)) {
                bridge.eval("() => (bot.setProfile ? bot.setProfile(null) : (bot.profile = null, window.__wowProfile = null))");
            }
            return JSON.writeValueAsString(Map.of("result", "профиль сброшен (база не настроена)", "profile", name,
                    "dbEnabled", false));
        }
        db.deleteProfileData(name);
        bridge.ensureReady();
        String active = String.valueOf(bridge.eval("() => bot.profile"));
        if (name.equals(active)) {
            bridge.eval("() => (bot.setProfile ? bot.setProfile(null) : (bot.profile = null, window.__wowProfile = null))");
        }
        return JSON.writeValueAsString(Map.of("result", "данные профиля удалены", "profile", name));
    }

    @Tool(name = "bags", description = """
            Показывает состояние сумок: свободно/всего слотов (обычные сумки), сколько предметов к продаже, \
            деньги, открыто ли окно торговца и т.д.""")
    public String bags() {
        bridge.ensureReady();
        Object raw = bridge.eval("() => JSON.stringify(bot.bags ? bot.bags() : {})");
        try {
            return JSON.writeValueAsString(Map.of("bags", JSON.readValue(String.valueOf(raw), Object.class)));
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of("bags", raw));
        }
    }

    @Tool(name = "remember_vendor", description = """
            Запомнить торговца в ТЕКУЩЕЙ точке игрока (zone/x/y из get_state). Игрок должен стоять рядом с NPC.""")
    public String rememberVendor(
            @ToolParam(description = "Имя торговца (как в игре, для /target)") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name не должен быть пустым");
        }
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — торговец не сохранён"));
        }
        GameState state = GameState.parse(bridge.report());
        // facing нужен, чтобы бот при продаже смотрел туда же, куда игрок при запоминании
        db.rememberVendor(state.zone(), state.x(), state.y(), name.trim(), state.facing());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "торговец сохранён");
        result.put("zone", state.zone());
        result.put("x", state.x());
        result.put("y", state.y());
        result.put("facing", state.facing());
        result.put("name", name.trim());
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "vendors", description = "Список торговцев текущей зоны.")
    public String vendors() {
        GameState state = GameState.parse(bridge.report());
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", state.zone(), "vendors", List.of()));
        }
        List<Map<String, Object>> list = db.listVendors(state.zone());
        // ensure numeric types are safe in output (already int/double from DbService)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", state.zone());
        result.put("vendors", list);
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "sell_now", description = """
            Продать хлам и отремонтироваться у ближайшего известного торговца текущей зоны. \
            Запускается в фоне — результат смотри через wait_job.""")
    public String sellNow() {
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — торговец неизвестен"));
        }
        GameState state = GameState.parse(bridge.report());
        Map<String, Object> v = db.nearestVendor(state.zone(), state.x(), state.y());
        if (v == null) {
            return JSON.writeValueAsString(Map.of("result", "торговец в текущей зоне не задан", "zone", state.zone()));
        }
        return jsonResult(bridge.start("sellAt", List.of(vendorArg(v))));
    }

    /** Запись торговца из БД → аргумент bot.sellAt (facing может отсутствовать в старых записях). */
    private static Map<String, Object> vendorArg(Map<String, Object> v) {
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("x", ((Number) v.get("x")).doubleValue());
        vendor.put("y", ((Number) v.get("y")).doubleValue());
        vendor.put("name", String.valueOf(v.get("name")));
        Object facing = v.get("facing");
        if (facing instanceof Number n) {
            vendor.put("facing", n.doubleValue());
        }
        return vendor;
    }

    @Tool(name = "keep_item", description = """
            Добавить предмет в список KEEP (не продавать). Сохраняет в БД и сразу применяет в игре.""")
    public String keepItem(
            @ToolParam(description = "ID предмета (itemId)") Integer itemId) {
        if (itemId == null || itemId <= 0) {
            throw new IllegalArgumentException("itemId должен быть положительным целым");
        }
        if (db.isEnabled()) {
            db.addKeepItem(itemId);
        }
        bridge.ensureReady();
        try {
            bridge.eval("(id) => chat('/run KEEP['+id+']=true')", itemId);
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of(
                    "result", "сохранено в БД, но не удалось применить в игре: " + e.getMessage(),
                    "itemId", itemId,
                    "dbEnabled", db.isEnabled()));
        }
        return JSON.writeValueAsString(Map.of("result", "предмет добавлен в KEEP", "itemId", itemId, "dbEnabled", db.isEnabled()));
    }

    @Tool(name = "reload_bot", description = """
            Перезагружает bot.js прямо в открытой странице игры, без её перезагрузки: \
            останавливает старого бота, удаляет window.bot и заново загружает скрипт, \
            собранный из модулей по manifest.txt, после чего повторяет Lua-подготовку \
            и поиск таблицы. Если задано свойство wow.bot-js-dir, модули читаются с диска \
            при каждом вызове — пересборка не нужна; иначе берутся из classpath.""")
    public String reloadBot() {
        return jsonResult(bridge.reloadBot());
    }

    private static boolean isRunning(String report) {
        try {
            GameState.Job job = GameState.parse(report).job();
            return job != null && "running".equals(job.status());
        } catch (RuntimeException e) {
            log.warn("Не разобрал ответ bot.report(): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Валидирует и нормализует ротацию: только key '1'..'9' и необязательный maxHpPct 0..1.
     * В страницу передаются уже проверенные значения, без склейки JS-строк.
     */
    static List<Map<String, Object>> parseRotation(String rotationJson) {
        if (rotationJson == null || rotationJson.isBlank()) {
            throw new IllegalArgumentException(
                    "rotationJson не должен быть пустым. Ожидается JSON-массив вида "
                            + "[{\"key\":\"3\",\"maxHpPct\":0.4},{\"key\":\"2\"},{\"key\":\"1\"}]");
        }

        Object parsed;
        try {
            parsed = JSON.readValue(rotationJson, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rotationJson должен быть корректным JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "rotationJson должен быть непустым JSON-массивом объектов вида "
                            + "{\"key\":\"3\",\"maxHpPct\":0.4}");
        }

        List<Map<String, Object>> rotation = new ArrayList<>();
        Set<String> usedIdentities = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            int position = i + 1;
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException(
                        "Элемент #" + position + " должен быть объектом с полем key ('1'..'9') или spell (nameHash)");
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            Object spellValue = item.get("spell");
            Object keyValue = item.get("key");
            if (spellValue != null) {
                if (!(spellValue instanceof Number spellNumber) || spellNumber.doubleValue() != Math.rint(spellNumber.doubleValue())) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": spell должен быть целым nameHash, получено: " + spellValue);
                }
                int spell = spellNumber.intValue();
                if (!usedIdentities.add("spell:" + spell)) {
                    throw new IllegalArgumentException("Заклинание (nameHash " + spell + ") указано в ротации больше одного раза");
                }
                entry.put("spell", spell);
            } else {
                String key = slotKey(keyValue, "key", position);
                if (!usedIdentities.add("key:" + key)) {
                    throw new IllegalArgumentException("Слот " + key + " указан в ротации больше одного раза");
                }
                entry.put("key", key);
            }

            Object maxHpPctValue = item.get("maxHpPct");
            if (maxHpPctValue != null) {
                if (!(maxHpPctValue instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": maxHpPct должен быть числом от 0 до 1, получено: "
                                    + maxHpPctValue);
                }
                double threshold = number.doubleValue();
                if (Double.isNaN(threshold) || threshold < 0 || threshold > 1) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": maxHpPct должен быть от 0 до 1, получено: "
                                    + maxHpPctValue);
                }
                entry.put("maxHpPct", threshold);
            }

            Object outOfCombatValue = item.get("outOfCombatOnly");
            if (outOfCombatValue != null) {
                if (!(outOfCombatValue instanceof Boolean outOfCombatOnly)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": outOfCombatOnly должен быть true/false, получено: "
                                    + outOfCombatValue);
                }
                entry.put("outOfCombatOnly", outOfCombatOnly);
            }

            Object waitAfterValue = item.get("waitAfter");
            if (waitAfterValue != null) {
                if (!(waitAfterValue instanceof Map<?, ?> waitAfter)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": waitAfter должен быть объектом {key, ms}, получено: "
                                    + waitAfterValue);
                }
                Object waitMsValue = waitAfter.get("ms");
                if (!(waitMsValue instanceof Number msNumber)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": waitAfter.ms должен быть числом от 0 до 10000, получено: "
                                    + waitMsValue);
                }
                double waitMs = msNumber.doubleValue();
                if (!Double.isFinite(waitMs) || waitMs < 0 || waitMs > 10_000 || waitMs != Math.rint(waitMs)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": waitAfter.ms должен быть целым числом от 0 до 10000, "
                                    + "получено: " + waitMsValue);
                }
                Object waitSpellValue = waitAfter.get("spell");
                Map<String, Object> waitAfterEntry = new LinkedHashMap<>();
                if (waitSpellValue != null) {
                    if (!(waitSpellValue instanceof Number ws) || ws.doubleValue() != Math.rint(ws.doubleValue())) {
                        throw new IllegalArgumentException(
                                "Элемент #" + position + ": waitAfter.spell должен быть целым nameHash, получено: "
                                        + waitSpellValue);
                    }
                    waitAfterEntry.put("spell", ws.intValue());
                } else {
                    String waitKey = slotKey(waitAfter.get("key"), "waitAfter.key", position);
                    waitAfterEntry.put("key", waitKey);
                }
                waitAfterEntry.put("ms", (int) waitMs);
                entry.put("waitAfter", waitAfterEntry);
            }

            rotation.add(entry);
        }
        return rotation;
    }

    /** Слот панели '1'..'9' из JSON-значения (строка или целое число). */
    private static String slotKey(Object value, String field, int position) {
        String key = value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                ? String.valueOf(number.intValue())
                : String.valueOf(value);
        if (value == null || !key.matches("[1-9]")) {
            throw new IllegalArgumentException(
                    "Элемент #" + position + ": " + field + " должен быть слотом '1'..'9', получено: " + value);
        }
        return key;
    }

    private static int normalizeWaitSeconds(Integer maxSeconds) {
        if (maxSeconds == null) {
            return WAIT_JOB_DEFAULT_SECONDS;
        }
        return Math.max(1, Math.min(WAIT_JOB_MAX_SECONDS, maxSeconds));
    }

    private static void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }

    private static void validateCoordinates(double x, double y) {
        if (Double.isNaN(x) || x < 0 || x > 100) {
            throw new IllegalArgumentException(
                    "x должен быть числом от 0 до 100 (координаты зоны), получено: " + x);
        }
        if (Double.isNaN(y) || y < 0 || y > 100) {
            throw new IllegalArgumentException(
                    "y должен быть числом от 0 до 100 (координаты зоны), получено: " + y);
        }
    }

    private static String jsonResult(String text) {
        return JSON.writeValueAsString(Map.of("result", text));
    }
}
