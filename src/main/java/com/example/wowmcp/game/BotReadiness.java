package com.example.wowmcp.game;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Готовность бота: загрузка {@code window.bot} в страницу, Lua-подготовка через чат,
 * поиск таблиц в памяти и защита от бесконечных попыток ({@code luaBlocked}).
 *
 * <p>Все методы, кроме {@link #reset()}, вызываются только из потока Playwright.
 */
@Component
class BotReadiness {

    private static final Logger log = LoggerFactory.getLogger(BotReadiness.class);

    /** JS-условие «бот уже загружен в страницу». */
    static final String BOT_LOADED_JS = "typeof window.bot !== 'undefined'";

    /** Страховка на стороне Java: подготовка + сам вызов инструмента. */
    static final long READY_OPERATION_TIMEOUT_MS = 110_000;

    private static final long STATE_TIME_WINDOW_MS = 1000;
    /** Пауза после bot.stop() перед удалением бота: чтобы клавиши успели отпуститься. */
    private static final long RELOAD_RELEASE_KEYS_PAUSE_MS = 200;

    /** Сколько раз пробуем Lua-подготовку, прежде чем сдаться. */
    private static final int LUA_SETUP_ATTEMPTS = 2;
    /** Общий бюджет на всю подготовку бота (все попытки вместе). */
    private static final long READY_TIMEOUT_MS = 100_000;

    private static final String LUA_NOT_FOUND_MESSAGE =
            "Lua-таблица не найдена: проверь, что персонаж в игре и чат не занят";

    private static final String READY_TIMEOUT_MESSAGE =
            "Lua-подготовка не уложилась в " + (READY_TIMEOUT_MS / 1000) + " с (попыток: "
                    + LUA_SETUP_ATTEMPTS + "). "
                    + "Игра на паузе или окно Chrome свёрнуто? Разверни окно игры и повтори.";

    /**
     * {@code bot.find()} с таймаутом на стороне страницы: если таймеры вкладки
     * заморожены, промис не повиснет навсегда, а вернёт {@code timeout}.
     */
    private static final String FIND_WITH_TIMEOUT_JS =
            "(ms) => Promise.race(["
                    + "bot.find().then(() => 'ok', e => 'error:' + (e && e.message ? e.message : e)),"
                    + "new Promise(resolve => setTimeout(() => resolve('timeout'), ms))"
                    + "])";

    private final PageConnection connection;
    private final BotScriptLoader scriptLoader;
    private final LuaSources luaSources;
    private final GameSessionSync sessionSync;

    /** Время bot.state().time на момент начала Lua-подготовки — для определения, тикали ли таймеры страницы. */
    private volatile double initialTimeForSetup;

    /** Блокировка автоматических подготовок после 2 неудач подряд (сбрасывается reload_bot). */
    private volatile boolean luaBlocked = false;
    private volatile int consecutiveLuaFailures = 0;

    BotReadiness(PageConnection connection, BotScriptLoader scriptLoader,
                 LuaSources luaSources, GameSessionSync sessionSync) {
        this.connection = connection;
        this.scriptLoader = scriptLoader;
        this.luaSources = luaSources;
        this.sessionSync = sessionSync;
    }

    private Page page() {
        return connection.page();
    }

    /** Снимает блокировку подготовок (reload_bot). Можно вызывать из любого потока. */
    void reset() {
        luaBlocked = false;
        consecutiveLuaFailures = 0;
    }

    /**
     * Фоновое подключение сразу после старта сервера: находим вкладку игры и, если нужно,
     * загружаем bot.js. Lua-подготовки здесь нет (она печатает в чат) — только чтобы
     * события (убийства, применения заклинаний) начали писаться без первой команды.
     */
    void warmUp() {
        try {
            connection.ensureConnected();
            boolean injected = false;
            if (!isBotLoaded()) {
                injectBot();
                sessionSync.autoLoadProfile();
                injected = true;
            }
            try {
                page().evaluate("() => bot.find()");
            } catch (PlaywrightException e) {
                log.debug("Фоновый bot.find() не удался: {}", e.getMessage());
            }
            log.info("Фоновое подключение к игре готово (бот {}): события пишутся без вызова инструментов",
                    injected ? "загружен, профиль " + page().evaluate("() => bot.profile") : "уже был в странице");
        } catch (RuntimeException e) {
            log.info("Фоновое подключение к игре не удалось: {} — подключусь при первом вызове инструмента",
                    e.getMessage());
        }
    }

    /**
     * Перезагружает bot.js в странице игры без перезагрузки самой страницы.
     *
     * <p>Останавливает бота (чтобы отпустились клавиши), удаляет {@code window.bot}
     * и снова проходит обычную подготовку: скрипт вставляется заново, затем выполняется
     * Lua-подготовка и поиск таблицы.
     */
    String reloadBot() {
        log.info("Перезагружаю bot.js, старый бот останавливается");
        page().evaluate("() => { if (window.bot && window.bot.dispose) window.bot.dispose(); }");
        page().evaluate("() => { if (window.bot) window.bot.stop(); }");
        PageConnection.sleep(RELOAD_RELEASE_KEYS_PAUSE_MS);
        page().evaluate("() => { delete window.bot; }");
        try {
            ensureReady();
        } catch (RuntimeException e) {
            log.warn("reload_bot: подготовка не удалась: {}", e.getMessage());
            return "бот перезагружен, но подготовка не удалась: " + e.getMessage();
        }
        return "бот перезагружен: " + scriptLoader.describeSource();
    }

    /** Загружен ли {@code window.bot}. */
    boolean isBotLoaded() {
        return Boolean.TRUE.equals(page().evaluate(BOT_LOADED_JS));
    }

    /** Загружает bot.js, ищет Lua-таблицу; при необходимости выполняет Lua-подготовку. */
    void ensureReady() {
        if (luaBlocked) {
            throw new IllegalStateException("подготовка заблокирована после неудач, зайди в игру и вызови reload_bot");
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS);
        boolean injected = false;
        if (!isBotLoaded()) {
            injectBot();
            injected = true;
        }

        // Сначала пробуем найти таблицу без ввода Lua: она могла остаться в игре.
        initialTimeForSetup = safeStateTime();
        try {
            page().evaluate("() => bot.find()");
        } catch (PlaywrightException e) {
            log.debug("bot.find() без Lua не удался: {}", e.getMessage());
        }
        if (!isAddrUnset()) {
            Object v = page().evaluate("() => window.bot.state().luaVer");
            int ver = (v instanceof Number n) ? n.intValue() : -1;
            if (ver == luaSources.version() && isStateTimeAlive(deadline)) {
                log.info("Lua-таблица уже в игре (версия {}) — подготовка пропущена", ver);
                consecutiveLuaFailures = 0;
                if (injected) {
                    sessionSync.autoLoadProfile();
                }
                sessionSync.syncKeepItems();
                return;
            }
        }

        log.info("...выполняю подготовку");

        RuntimeException lastError = null;
        boolean notInWorld = false;
        for (int attempt = 1; attempt <= LUA_SETUP_ATTEMPTS; attempt++) {
            long attemptDeadline = attemptDeadline(deadline, attempt);
            long budgetMs = remainingMs(attemptDeadline);
            if (budgetMs <= 0) {
                log.warn("Lua-подготовка: попытка {}/{} не запущена — общий таймаут {} с исчерпан",
                        attempt, LUA_SETUP_ATTEMPTS, READY_TIMEOUT_MS / 1000);
                break;
            }
            log.info("Lua-подготовка: попытка {}/{} (бюджет {} мс, всего осталось {} мс)",
                    attempt, LUA_SETUP_ATTEMPTS, budgetMs, remainingMs(deadline));
            initialTimeForSetup = safeStateTime();
            try {
                runLuaSetup(attemptDeadline);
                consecutiveLuaFailures = 0;
                if (injected) {
                    sessionSync.autoLoadProfile();
                }
                sessionSync.syncKeepItems();
                return;
            } catch (RuntimeException e) {
                lastError = e;
                consecutiveLuaFailures++;
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not in world")) {
                    notInWorld = true;
                    if (consecutiveLuaFailures >= 2) {
                        luaBlocked = true;
                    }
                    break;
                }
                if (consecutiveLuaFailures >= 2) {
                    luaBlocked = true;
                }
                log.warn("Lua-подготовка: попытка {}/{} не удалась: {}",
                        attempt, LUA_SETUP_ATTEMPTS, e.getMessage());
            }
            if (attempt < LUA_SETUP_ATTEMPTS) {
                log.info("Повторяю Lua-подготовку");
            }
        }
        if (notInWorld) {
            throw new IllegalStateException("Персонаж не в мире (экран входа). Зайди в игру вручную", lastError);
        }
        if (luaBlocked) {
            throw new IllegalStateException("подготовка заблокирована после неудач, зайди в игру и вызови reload_bot");
        }
        throw lastError != null ? lastError : readyTimeout();
    }

    /** Версия JS на диске/в jar (то, что загрузится при «перезагрузи бота»). */
    String jsVersionOnDisk() {
        return scriptLoader.version();
    }

    /** Версия Lua в этом jar (в игре должна совпадать, иначе нужен ./deploy.sh). */
    int luaVersionJava() {
        return luaSources.version();
    }

    private void injectBot() {
        log.info("Загружаю модули bot.js в страницу игры ({})", scriptLoader.describeSource());
        String js = scriptLoader.read();
        page().evaluate("(src) => { (0, eval)(src); }", js);
        page().evaluate("(v) => { window.__botJsVer = v; }", BotScriptLoader.versionOf(js));
        if (!isBotLoaded()) {
            throw new IllegalStateException("Не удалось загрузить bot.js: window.bot не появился");
        }
    }

    /**
     * Вводит Lua-строки в чат и ищет таблицу. Ни один шаг не ждёт дольше
     * {@code attemptDeadline}: если игра зависла или вкладка заморожена,
     * бросаем {@link #READY_TIMEOUT_MESSAGE}, а не висим.
     */
    private void runLuaSetup(long attemptDeadline) {
        List<String> luaSetup = luaSources.setupLines();
        log.info("Выполняю Lua-подготовку ({} строк)", luaSetup.size());
        page().evaluate("() => { const c = document.getElementById('canvas'); if (c) c.focus(); }");
        for (String line : luaSetup) {
            long remaining = remainingMs(attemptDeadline);
            if (remaining <= 0) {
                throw readyTimeout();
            }
            page().evaluate("t => chat(t)", line);
            PageConnection.sleep(Math.min(PageConnection.CHAT_LINE_PAUSE_MS, remainingMs(attemptDeadline)));
        }
        long remaining = remainingMs(attemptDeadline);
        if (remaining <= 0) {
            throw readyTimeout();
        }
        // Playwright дождётся промиса, который вернёт find(); сам find ограничен
        // таймаутом, чтобы не зависнуть, если таймеры страницы заморожены.
        // Важно: аргумент — int, а не long: Playwright умеет передавать в страницу
        // только Integer/Double, на Long падает «Unsupported type of argument».
        Object result = page().evaluate(FIND_WITH_TIMEOUT_JS, toPageTimeoutMs(remaining));
        if ("timeout".equals(result)) {
            throw readyTimeout();
        }
        if (!"ok".equals(result)) {
            throw new IllegalStateException("bot.find() завершился с ошибкой: " + result);
        }
        if (isAddrUnset()) {
            throw new IllegalStateException(LUA_NOT_FOUND_MESSAGE);
        }
        log.info("Бот готов: {}", page().evaluate("() => JSON.stringify(bot.state())"));
    }

    private boolean isAddrUnset() {
        return Boolean.TRUE.equals(page().evaluate(
                "typeof window.bot !== 'undefined' && window.bot.addr === null"));
    }

    /** GetTime() из игры должен тикать; если нет — интерфейс перезагрузили. */
    private boolean isStateTimeAlive(long deadline) {
        long windowMs = Math.min(STATE_TIME_WINDOW_MS, remainingMs(deadline));
        if (windowMs <= 0) {
            return false;
        }
        double before = stateTime();
        PageConnection.sleep(windowMs);
        double after = stateTime();
        return before > 0 && after != before;
    }

    private double stateTime() {
        Object value = page().evaluate("() => window.bot.state().time");
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    /** Безопасное чтение bot.state().time (0, если таблица ещё не найдена). */
    private double safeStateTime() {
        try {
            if (page() == null || page().isClosed() || isAddrUnset()) {
                return 0d;
            }
            return stateTime();
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    private IllegalStateException readyTimeout() {
        boolean ticked = false;
        try {
            double now = safeStateTime();
            ticked = initialTimeForSetup > 0 && now != initialTimeForSetup;
        } catch (RuntimeException ignored) {
        }
        int secs = (int) (READY_TIMEOUT_MS / 1000);
        if (ticked) {
            return new IllegalStateException(
                    "Lua-подготовка не успела за " + secs + " с");
        }
        return new IllegalStateException(READY_TIMEOUT_MESSAGE);
    }

    /** Бюджет попытки: первая попытка получает весь оставшийся бюджет. */
    private static long attemptDeadline(long deadline, int attempt) {
        if (attempt == 1) {
            return deadline;
        }
        return System.nanoTime() + remainingNanos(deadline);
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static long remainingMs(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline));
    }

    /** Playwright передаёт в страницу только Integer/Double — приводим long к int с ограничением. */
    static int toPageTimeoutMs(long ms) {
        if (ms <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }
}
