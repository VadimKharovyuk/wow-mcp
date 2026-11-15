package com.example.wowmcp.tools;

import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.example.wowmcp.tools.ToolSupport.jsonResult;
import static com.example.wowmcp.tools.ToolSupport.validateCoordinates;

/**
 * MCP-инструменты управления ботом: состояние, движение, бой, добыча, отдых, остановка.
 * Java только вызывает функции bot.js; циклы ходьбы, боя и сбора добычи остаются в браузере.
 */
@Component
public class BotActionTools {

    private static final Logger log = LoggerFactory.getLogger(BotActionTools.class);

    private static final int WAIT_JOB_DEFAULT_SECONDS = 30;
    private static final int WAIT_JOB_MAX_SECONDS = 60;

    private final GameBridge bridge;
    private final Navigator navigator;

    public BotActionTools(GameBridge bridge, Navigator navigator) {
        this.bridge = bridge;
        this.navigator = navigator;
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

    @Tool(name = "go_to", description = """
            Бежать к точке (x, y в координатах зоны 0–100), по пути отбиваясь от мобов, \
            а после боя собирая добычу (autoLoot). Запускается в фоне и сразу возвращает \
            ответ — результат потом смотри через wait_job.""")
    public String goTo(
            @ToolParam(description = "Координата X в зоне, от 0 до 100") double x,
            @ToolParam(description = "Координата Y в зоне, от 0 до 100") double y,
            @ToolParam(required = false, description = "Собирать добычу после боя, по умолчанию true")
            Boolean autoLoot,
            @ToolParam(required = false, description = "Идти по графу дорог, если он помогает (build_roads), по умолчанию true")
            Boolean useRoads) {
        validateCoordinates(x, y);
        boolean loot = autoLoot == null || autoLoot;
        return navigator.start(x, y, true, loot, useRoads == null || useRoads);
    }

    @Tool(name = "move_to", description = """
            Бежать к точке (x, y в координатах зоны 0–100) без боя. \
            Останавливается, если напали мобы. Работает в фоне — результат смотри через wait_job.""")
    public String moveTo(
            @ToolParam(description = "Координата X в зоне, от 0 до 100") double x,
            @ToolParam(description = "Координата Y в зоне, от 0 до 100") double y,
            @ToolParam(required = false, description = "Идти по графу дорог, если он помогает (build_roads), по умолчанию true")
            Boolean useRoads) {
        validateCoordinates(x, y);
        return navigator.start(x, y, false, false, useRoads == null || useRoads);
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

    @Tool(name = "look_around", description = """
            Найти цель сканом экрана мышью (вся видимая область, не только впереди). \
            Работает в фоне — результат смотри через wait_job.""")
    public String lookAround() {
        return jsonResult(bridge.start("lookAround", List.of()));
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

    @Tool(name = "revive", description = """
            Воскресить персонажа: отпустить дух, добежать до трупа, дождаться задержки и воскреснуть, \
            затем отдохнуть. Работает в фоне — результат смотри через wait_job. \
            Если персонаж жив — ответит «персонаж жив» и ничего не сделает.""")
    public String revive() {
        GameState state = GameState.parse(bridge.report());
        if (!state.dead() && !state.ghost()) {
            return jsonResult("персонаж жив");
        }
        return jsonResult(bridge.start("revive", List.of()));
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
}
