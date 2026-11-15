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
    public boolean isBotLoadedIfConnected() {
        // Пока идёт тяжёлая операция, не трогаем страницу: иначе таймаут фонового
        // сборщика событий прервёт выполняющуюся операцию.
        if (connection.isBusy()) {
            return false;
        }
        try {
            return connection.callOnce(2000, () -> {
                if (connection.page() == null || connection.page().isClosed()) {
                    return false;
                }
                try {
                    return Boolean.TRUE.equals(connection.page().evaluate(BOT_LOADED_JS));
                } catch (PlaywrightException e) {
                    return false;
                }
            });
        } catch (RuntimeException e) {
            return false;
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
