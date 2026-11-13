package com.example.wowmcp.tools;

import com.example.wowmcp.db.DbService;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
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

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final GameBridge bridge;
    private final DbService db;

    private volatile String lastRecordSession;
    private volatile java.time.Instant lastRecordStart;

    public WowTools(GameBridge bridge, DbService db) {
        this.bridge = bridge;
        this.db = db;
    }

    @Tool(name = "get_state", description = """
            Показывает состояние персонажа: координаты x/y в зоне (0–100), процент HP, \
            идёт ли бой, есть ли цель и какая задача сейчас выполняется. Поле ready — маска \
            готовых умений панели 1–9, moDead — под курсором труп, lootN — предметов в окне добычи, \
            tLvl/tFlags/tDist — уровень/флаги/дистанция цели, manaPct — мана, grind — статистика гринда.""")
    public String getState() {
        return bridge.report();
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
            убить, собрать добычу, отдохнуть, повторить. Работает в фоне. Возвращается по \
            лимиту убийств/времени или при проблеме (смерть, застрял, сумки полные, мобов нет). \
            Во время охоты вызывай wait_job; статистика — в поле grind.""")
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
            String drinkKey) {
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
        List<Object> args = opts.isEmpty() ? List.of() : List.of(opts);
        return jsonResult(bridge.start("grind", args));
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
            (центр, количество, средний уровень моба, последнее убийство). Топ-30 по количеству. \
            Без БД — сообщение «база не настроена».""")
    public String spots() {
        bridge.ensureReady();
        GameState state = GameState.parse(bridge.report());
        int zone = state.zone();
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", zone));
        }
        List<Map<String, Object>> rows = db.querySpots(zone);
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
            Задать приоритет умений: список слотов панели 1–9 от самого важного к основному удару. \
            Формат: JSON-массив [{"key":"2","maxHpPct":0.35},{"key":"4","waitAfter":{"key":"2","ms":1000}}]. \
            maxHpPct необязателен (0..1): умение применяется, только если HP ниже этой доли. \
            outOfCombatOnly (true/false) — умение только вне боя. \
            waitAfter {key, ms} — не переходить к следующим умениям, пока с нажатия key прошло меньше ms.""")
    public String setRotation(
            @ToolParam(description = "JSON-массив вида [{\"key\":\"2\",\"maxHpPct\":0.35},"
                    + "{\"key\":\"4\",\"maxHpPct\":0.35,\"waitAfter\":{\"key\":\"2\",\"ms\":1000}},"
                    + "{\"key\":\"3\",\"outOfCombatOnly\":true},{\"key\":\"1\"}]")
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
            Показывает текущий приоритет умений (массив слотов панели 1–9 с необязательными \
            maxHpPct, outOfCombatOnly и waitAfter).""")
    public String getRotation() {
        bridge.ensureReady();
        return String.valueOf(bridge.eval("() => JSON.stringify(bot.rotation)"));
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
        Set<String> usedKeys = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            int position = i + 1;
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException(
                        "Элемент #" + position + " должен быть объектом с полем key ('1'..'9')");
            }

            Object keyValue = item.get("key");
            String key = slotKey(keyValue, "key", position);
            if (!usedKeys.add(key)) {
                throw new IllegalArgumentException("Слот " + key + " указан в ротации больше одного раза");
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);

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
                Object waitKeyValue = waitAfter.get("key");
                String waitKey = slotKey(waitKeyValue, "waitAfter.key", position);

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
                entry.put("waitAfter", Map.of("key", waitKey, "ms", (long) waitMs));
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
