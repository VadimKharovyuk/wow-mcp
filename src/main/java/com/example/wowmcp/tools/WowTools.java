package com.example.wowmcp.tools;

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

    public WowTools(GameBridge bridge) {
        this.bridge = bridge;
    }

    @Tool(name = "get_state", description = """
            Показывает состояние персонажа: координаты x/y в зоне (0–100), процент HP, \
            идёт ли бой, есть ли цель и какая задача сейчас выполняется. Поле ready — маска \
            готовых умений панели 1–9, moDead — под курсором труп, lootN — предметов в окне добычи.""")
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

    @Tool(name = "wait_job", description = """
            Ждёт завершения текущей задачи бота (go_to/move_to/fight/loot/rest), опрашивая \
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
            Формат: JSON-массив [{"key":"3","maxHpPct":0.4},{"key":"2"},{"key":"1"}]. \
            maxHpPct необязателен (0..1): умение применяется, только если HP ниже этой доли.""")
    public String setRotation(
            @ToolParam(description = "JSON-массив вида [{\"key\":\"3\",\"maxHpPct\":0.4},{\"key\":\"2\"},{\"key\":\"1\"}]")
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
            Показывает текущий приоритет умений (массив слотов панели 1–9 с необязательным maxHpPct).""")
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
            String key = keyValue instanceof Number number
                    ? String.valueOf(number.intValue())
                    : String.valueOf(keyValue);
            if (keyValue == null || !key.matches("[1-9]")) {
                throw new IllegalArgumentException(
                        "Элемент #" + position + ": key должен быть слотом '1'..'9', получено: " + keyValue);
            }
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

            rotation.add(entry);
        }
        return rotation;
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
