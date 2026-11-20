package com.example.wowmcp.game;

import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.wowmcp.game.BotReadiness.BOT_LOADED_JS;
import static com.example.wowmcp.game.BotReadiness.READY_OPERATION_TIMEOUT_MS;

/**
 * Фасад для инструментов MCP и фонового сборщика событий: мост в запущенный Chrome с игрой.
 *
 * <p>Java не управляет персонажем в цикле: вся логика ходьбы/боя живёт в bot.js,
 * здесь только вызовы {@code bot.*}. Сама работа разнесена по классам:
 * <ul>
 *   <li>{@link PageConnection} — Playwright, CDP, поток, переподключение;</li>
 *   <li>{@link BotReadiness} — загрузка бота и Lua-подготовка;</li>
 *   <li>{@link BotScriptLoader}, {@link LuaSources}, {@link LuaMinifier} — исходники бота и Lua;</li>
 *   <li>{@link GameSessionSync} — перенос профиля и KEEP из БД в игру.</li>
 * </ul>
 */
@Component
public class GameBridge {

    private static final Logger log = LoggerFactory.getLogger(GameBridge.class);

    private final PageConnection connection;
    private final BotReadiness readiness;

    GameBridge(PageConnection connection, BotReadiness readiness) {
        this.connection = connection;
        this.readiness = readiness;
    }

    /**
     * Состояние для самопроверки: занята ли страница, загружен ли бот, версии JS/Lua на диске и в игре.
     * Не подключается к Chrome и не запускает подготовку. Безопасно вызывать из фоновых потоков.
     */
    public Map<String, Object> health() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("busy", connection.isBusy());
        boolean loaded = isBotLoadedIfConnected();
        m.put("botLoaded", loaded);
        try {
            m.put("jsVerDisk", readiness.jsVersionOnDisk());
            m.put("luaVerJava", readiness.luaVersionJava());
        } catch (RuntimeException e) {
            m.put("versionError", String.valueOf(e.getMessage()));
        }
        if (loaded) {
            // третья часть: «1» — поля Lua-цепочки (уровень игрока, зона, мана…) заполняются, «0» — нет (живой персонаж, уровень 0)
            Optional<String> r = evalIfReady("(() => { try { const s = window.bot.state(); return (window.__botJsVer || '') + '|' + (s.luaVer"
                    + " === undefined ? '' : s.luaVer) + '|' + ((s.hp > 0 && s.pLvl === 0) ? '0' : '1'); } catch (e) { return '||'; } })()", 2000);
            if (r.isPresent()) {
                String[] p = r.get().split("\\|", -1);
                m.put("jsVerPage", p.length > 0 && !p[0].isEmpty() ? p[0] : null);
                m.put("luaVerPage", p.length > 1 && !p[1].isEmpty() ? p[1] : null);
                if (p.length > 2 && !p[2].isEmpty()) m.put("luaDataOk", "1".equals(p[2]));
            }
        }
        return m;
    }

    /** Фоновое подключение сразу после старта сервера (см. {@link BotReadiness#warmUp()}). */
    @EventListener(ApplicationReadyEvent.class)
    public void connectOnStartup() {
        connection.submit(readiness::warmUp);
    }

    /** Сырой JSON от {@code bot.report()} с предварительной проверкой готовности бота. */
    public String report() {
        return connection.call(READY_OPERATION_TIMEOUT_MS, () -> {
            readiness.ensureReady();
            return (String) connection.page().evaluate("() => bot.report()");
        });
    }

    /** {@code bot.report()} без проверки готовности — для частого опроса в wait_job. */
    public String reportRaw() {
        return connection.call(() -> (String) connection.page().evaluate("() => bot.report()"));
    }

    /** Запускает фоновую задачу бота ({@code bot.start(name, ...args)}). */
    public String start(String name, List<?> args) {
        return connection.call(READY_OPERATION_TIMEOUT_MS, () -> {
            readiness.ensureReady();
            Object result = connection.page().evaluate("(p) => bot.start(p.name, ...p.args)",
                    Map.of("name", name, "args", args));
            return String.valueOf(result);
        });
    }

    /** Экстренная остановка: не ждём Lua-подготовки, просто гасим бота, если он загружен. */
    public String stop() {
        return connection.call(() -> (String) connection.page().evaluate(
                "() => { if (window.bot) { bot.stop(); return 'остановлено'; } "
                        + "return 'бот не загружен — останавливать нечего'; }"));
    }

    /** Проверяет, что bot.js загружен, Lua-таблица найдена и игра не перезагружалась. */
    public void ensureReady() {
        connection.call(READY_OPERATION_TIMEOUT_MS, () -> {
            readiness.ensureReady();
            return null;
        });
    }

    /** Перезагружает bot.js в странице игры без перезагрузки самой страницы. */
    public String reloadBot() {
        readiness.reset();
        return connection.call(READY_OPERATION_TIMEOUT_MS, readiness::reloadBot);
    }

    /** Произвольное выражение/функция в контексте страницы игры. */
    public Object eval(String js) {
        return connection.call(() -> connection.page().evaluate(js));
    }

    /** Вызов функции с аргументом — Playwright сам сериализует его в JS. */
    public Object eval(String fn, Object arg) {
        return connection.call(() -> connection.page().evaluate(fn, arg));
    }

    /**
     * Не подключается к Chrome и не запускает Lua-подготовку: возвращает true только если
     * страница уже открыта и bot.js загружен. Безопасно вызывать из фоновых потоков.
     */
    /** Занята ли страница тяжёлой операцией (для диагностики сборщика событий). */
    public boolean isBusy() {
        return connection.isBusy();
    }

    /** Когда в последний раз писали в лог причину «бот не загружен» (не чаще раза в минуту). */
    private volatile long lastNotLoadedLogAt;

    private boolean notLoaded(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastNotLoadedLogAt > 60_000) {
            lastNotLoadedLogAt = now;
            log.info("Бот не загружен в странице: {}", reason);
        }
        return false;
    }

    public boolean isBotLoadedIfConnected() {
        // Пока идёт тяжёлая операция, не трогаем страницу: иначе таймаут фонового
        // сборщика событий прервёт выполняющуюся операцию.
        if (connection.isBusy()) {
            return false;
        }
        try {
            Object r = connection.callOnce(2000, () -> {
                if (connection.page() == null) {
                    return (Object) "страницы нет (нет подключения к игре)";
                }
                if (connection.page().isClosed()) {
                    return (Object) "страница закрыта";
                }
                try {
                    return Boolean.TRUE.equals(connection.page().evaluate(BOT_LOADED_JS)) ? (Object) Boolean.TRUE : (Object) "window.bot не определён (страницу перезагрузили?)";
                } catch (PlaywrightException e) {
                    return (Object) ("ошибка проверки: " + String.valueOf(e.getMessage()).split("\\R")[0]);
                }
            });
            return Boolean.TRUE.equals(r) || (r != null && notLoaded(String.valueOf(r)));
        } catch (RuntimeException e) {
            return notLoaded("исключение: " + String.valueOf(e.getMessage()).split("\\R")[0]);
        }
    }

    /**
     * Выполняет JS только если бот уже загружен и подключён; иначе возвращает empty.
     * Не вызывает ensureReady и не подключается к Chrome.
     */
    public Optional<String> evalIfReady(String js, long timeoutMs) {
        if (connection.isBusy()) {
            return Optional.empty();
        }
        if (!isBotLoadedIfConnected()) {
            return Optional.empty();
        }
        try {
            Object result = connection.callOnce(timeoutMs, () -> connection.page().evaluate(js));
            return Optional.of(result == null ? "" : String.valueOf(result));
        } catch (RuntimeException e) {
            log.debug("evalIfReady('{}') не удался: {}", js, e.getMessage());
            return Optional.empty();
        }
    }
}
