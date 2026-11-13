package com.example.wowmcp.game;

import com.example.wowmcp.config.WowProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Мост в запущенный Chrome с игрой.
 *
 * <p>Playwright не потокобезопасен, поэтому все обращения к {@link Page} выполняются
 * в одном выделенном потоке. Подключение к Chrome ленивое — при первом вызове инструмента.
 *
 * <p>Java не управляет персонажем в цикле: вся логика ходьбы/боя живёт в bot.js,
 * здесь только вызовы {@code bot.*}.
 */
@Component
public class GameBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameBridge.class);

    private static final String BOT_MANIFEST_RESOURCE = "bot/manifest.txt";
    private static final String BOT_MODULE_PREFIX = "bot/";
    private static final String BOT_MODULE_JOINER = ";\n";
    /** Версия Lua-части: (hashCode содержимого lua-setup.txt) & 0xFFFFF. */
    private static final int LUA_VERSION = (readResource("lua-setup.txt").hashCode() & 0xFFFFF);

    private static final List<String> LUA_SETUP = buildLuaSetup();

    private static final long LUA_LINE_PAUSE_MS = 300;
    private static final long STATE_TIME_WINDOW_MS = 1000;
    /** Пауза после bot.stop() перед удалением бота: чтобы клавиши успели отпуститься. */
    private static final long RELOAD_RELEASE_KEYS_PAUSE_MS = 200;

    /** Сколько раз пробуем Lua-подготовку, прежде чем сдаться. */
    private static final int LUA_SETUP_ATTEMPTS = 2;
    /** Общий бюджет на всю подготовку бота (все попытки вместе). */
    private static final long READY_TIMEOUT_MS = 45_000;
    /** Страховка на стороне Java: подготовка + сам вызов инструмента. */
    private static final long READY_OPERATION_TIMEOUT_MS = READY_TIMEOUT_MS + 5_000;

    private static final String LUA_NOT_FOUND_MESSAGE =
            "Lua-таблица не найдена: проверь, что персонаж в игре и чат не занят";

    private static final String READY_TIMEOUT_MESSAGE =
            "Lua-подготовка не уложилась в " + (READY_TIMEOUT_MS / 1000) + " с (попыток: "
                    + LUA_SETUP_ATTEMPTS + "). "
                    + "Игра на паузе или окно Chrome свёрнуто? Разверни окно игры и повтори.";

    private static final String NO_RESPONSE_MESSAGE =
            "Игра не ответила за " + (READY_OPERATION_TIMEOUT_MS / 1000) + " с. "
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

    private final WowProperties properties;

    private final ExecutorService playwrightThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wow-playwright");
        thread.setDaemon(true);
        return thread;
    });

    // Все поля ниже трогаем только из потока playwrightThread.
    private Playwright playwright;
    private Browser browser;
    private Page page;

    /**
     * Счётчик выполняющихся «тяжёлых» операций на странице (report/start/reloadBot/ensureReady/...).
     * Пока он больше нуля, фоновый {@link com.example.wowmcp.db.EventCollector} не должен
     * трогать страницу — иначе его таймаут прервёт выполняющуюся операцию.
     */
    private final AtomicInteger busy = new AtomicInteger(0);

    /** Время bot.state().time на момент начала Lua-подготовки — для определения, тикали ли таймеры страницы. */
    private volatile double initialTimeForSetup;

    public GameBridge(WowProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Публичный API для инструментов MCP
    // ------------------------------------------------------------------

    /** Сырой JSON от {@code bot.report()} с предварительной проверкой готовности бота. */
    public String report() {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(READY_OPERATION_TIMEOUT_MS, () -> retryOnDisconnect(() -> {
                ensureReadyInternal();
                return (String) evaluate("() => bot.report()");
            }));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** {@code bot.report()} без проверки готовности — для частого опроса в wait_job. */
    public String reportRaw() {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(() -> retryOnDisconnect(() -> (String) evaluate("() => bot.report()")));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Запускает фоновую задачу бота ({@code bot.start(name, ...args)}). */
    public String start(String name, List<?> args) {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(READY_OPERATION_TIMEOUT_MS, () -> retryOnDisconnect(() -> {
                ensureReadyInternal();
                Object result = page.evaluate("(p) => bot.start(p.name, ...p.args)",
                        Map.of("name", name, "args", args));
                return String.valueOf(result);
            }));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Экстренная остановка: не ждём Lua-подготовки, просто гасим бота, если он загружен. */
    public String stop() {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(() -> retryOnDisconnect(() -> (String) evaluate(
                    "() => { if (window.bot) { bot.stop(); return 'остановлено'; } "
                            + "return 'бот не загружен — останавливать нечего'; }")));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Проверяет, что bot.js загружен, Lua-таблица найдена и игра не перезагружалась. */
    public void ensureReady() {
        busy.incrementAndGet();
        try {
            onPlaywrightThread(READY_OPERATION_TIMEOUT_MS, () -> retryOnDisconnect(() -> {
                ensureReadyInternal();
                return null;
            }));
        } finally {
            busy.decrementAndGet();
        }
    }

    /**
     * Перезагружает bot.js в странице игры без перезагрузки самой страницы.
     *
     * <p>Останавливает бота (чтобы отпустились клавиши), удаляет {@code window.bot}
     * и снова проходит обычную подготовку: скрипт вставляется заново, затем выполняется
     * Lua-подготовка и поиск таблицы.
     */
    public String reloadBot() {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(READY_OPERATION_TIMEOUT_MS, () -> retryOnDisconnect(() -> {
                log.info("Перезагружаю bot.js, старый бот останавливается");
                page.evaluate("() => { if (window.bot && window.bot.dispose) window.bot.dispose(); }");
                page.evaluate("() => { if (window.bot) window.bot.stop(); }");
                sleep(RELOAD_RELEASE_KEYS_PAUSE_MS);
                page.evaluate("() => { delete window.bot; }");
                ensureReadyInternal();
                return "бот перезагружен: " + botJsSource();
            }));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Откуда берётся bot.js — для отчёта reload_bot. */
    private String botJsSource() {
        String dir = properties.botJsDir();
        return dir == null ? "из classpath (bot/manifest.txt)" : "с диска " + dir;
    }

    /** Произвольное выражение/функция в контексте страницы игры. */
    public Object eval(String js) {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(() -> retryOnDisconnect(() -> page.evaluate(js)));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Вызов функции с аргументом — Playwright сам сериализует его в JS. */
    public Object eval(String fn, Object arg) {
        busy.incrementAndGet();
        try {
            return onPlaywrightThread(() -> retryOnDisconnect(() -> page.evaluate(fn, arg)));
        } finally {
            busy.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // Подключение и переподключение (только в потоке playwrightThread)
    // ------------------------------------------------------------------

    private void ensureConnected() {
        if (page != null && !page.isClosed()) {
            return;
        }

        if (playwright == null) {
            log.info("Инициализирую Playwright (браузеры не скачиваем, работаем по CDP)");
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        }

        if (browser == null || !browser.isConnected()) {
            log.info("Подключаюсь к Chrome по CDP: {}", properties.cdpUrl());
            try {
                browser = playwright.chromium().connectOverCDP(properties.cdpUrl());
            } catch (PlaywrightException e) {
                throw new IllegalStateException(
                        "Не удалось подключиться к Chrome по CDP (" + properties.cdpUrl() + "). "
                                + "Запусти Chrome с --remote-debugging-port=9222 "
                                + "и открой вкладку игры.", e);
            }
        }

        page = findGamePage();
        log.info("Нашёл вкладку игры: {}", page.url());
    }

    private Page findGamePage() {
        for (BrowserContext context : browser.contexts()) {
            for (Page candidate : context.pages()) {
                if (candidate.url().contains(properties.pageUrlContains())) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException(
                "Вкладка с URL, содержащим \"" + properties.pageUrlContains() + "\", не найдена в Chrome ("
                        + properties.cdpUrl() + "). Открой игру в этом браузере.");
    }

    private void forgetPage() {
        // Важно: не вызываем browser.close() — для connectOverCDP это закрыло бы
        // сам Chrome пользователя. Просто теряем ссылки; новое подключение создастся заново.
        page = null;
        browser = null;
    }

    /**
     * Выполняет действие на странице; при обрыве соединения один раз переподключается
     * и повторяет действие.
     */
    private <T> T retryOnDisconnect(Supplier<T> action) {
        ensureConnected();
        try {
            return action.get();
        } catch (PlaywrightException e) {
            if (Thread.currentThread().isInterrupted() || stillConnected()) {
                // Это не обрыв связи, а ошибка самого вызова — повторять бессмысленно.
                throw e;
            }
            log.warn("Соединение с игрой упало ({}). Переподключаюсь и повторяю.", e.getMessage());
            forgetPage();
            ensureConnected();
            return action.get();
        }
    }

    /** Соединение с вкладкой всё ещё живо (значит, ошибка не сетевая). */
    private boolean stillConnected() {
        try {
            return page != null && !page.isClosed() && browser != null && browser.isConnected();
        } catch (PlaywrightException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Подготовка бота
    // ------------------------------------------------------------------

    private void ensureReadyInternal() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS);
        if (!isBotLoaded()) {
            injectBot();
        }

        // Сначала пробуем найти таблицу без ввода Lua: она могла остаться в игре.
        initialTimeForSetup = safeStateTime();
        try {
            page.evaluate("() => bot.find()");
        } catch (PlaywrightException e) {
            log.debug("bot.find() без Lua не удался: {}", e.getMessage());
        }
        if (!isAddrUnset()) {
            Object v = page.evaluate("() => window.bot.state().luaVer");
            int ver = (v instanceof Number n) ? n.intValue() : -1;
            if (ver == LUA_VERSION && isStateTimeAlive(deadline)) {
                log.info("Lua-таблица уже в игре (версия {}) — подготовка пропущена", ver);
                return;
            }
        }

        log.info("...выполняю подготовку");

        RuntimeException lastError = null;
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
                return;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Lua-подготовка: попытка {}/{} не удалась: {}",
                        attempt, LUA_SETUP_ATTEMPTS, e.getMessage());
            }
            if (attempt < LUA_SETUP_ATTEMPTS) {
                log.info("Повторяю Lua-подготовку");
            }
        }
        throw lastError != null ? lastError : readyTimeout();
    }

    /**
     * Пора ли выполнять Lua-подготовку: таблицы ещё нет или интерфейс игры
     * перезагружался ({@code bot.state().time} не тикает).
     */
    private boolean needsLuaSetup(long deadline) {
        if (isAddrUnset()) {
            return true;
        }
        if (isStateTimeAlive(deadline)) {
            return false;
        }
        log.warn("bot.state().time не меняется — похоже, интерфейс игры перезагружался. "
                + "Повторяю Lua-подготовку.");
        page.evaluate("() => { window.bot.addr = null; }");
        return true;
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

    /**
     * Приводит миллисекунды к {@code int} для передачи в страницу: Playwright
     * сериализует только {@link Integer} и {@link Double}, на {@link Long} бросает
     * «Unsupported type of argument».
     */
    /** Playwright передаёт в страницу только Integer/Double — приводим long к int с ограничением. */
    static int toPageTimeoutMs(long ms) {
        if (ms <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
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
                    "Lua-подготовка не успела за " + secs + " с (строк: " + LUA_SETUP.size() + ")");
        }
        return new IllegalStateException(READY_TIMEOUT_MESSAGE);
    }

    private boolean isBotLoaded() {
        return Boolean.TRUE.equals(page.evaluate("typeof window.bot !== 'undefined'"));
    }

    /**
     * Не подключается к Chrome и не запускает Lua-подготовку: возвращает true только если
     * страница уже открыта и bot.js загружен. Безопасно вызывать из фоновых потоков.
     */
    public boolean isBotLoadedIfConnected() {
        // Пока идёт тяжёлая операция, не трогаем страницу: иначе таймаут фонового
        // сборщика событий прервёт выполняющуюся операцию.
        if (busy.get() > 0) {
            return false;
        }
        try {
            return onPlaywrightThread(2000, () -> {
                if (page == null || page.isClosed()) {
                    return false;
                }
                try {
                    return Boolean.TRUE.equals(page.evaluate("typeof window.bot !== 'undefined'"));
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
        if (busy.get() > 0) {
            return Optional.empty();
        }
        if (!isBotLoadedIfConnected()) {
            return Optional.empty();
        }
        try {
            Object result = onPlaywrightThread(timeoutMs, () -> page.evaluate(js));
            return Optional.of(result == null ? "" : String.valueOf(result));
        } catch (RuntimeException e) {
            log.debug("evalIfReady('{}') не удался: {}", js, e.getMessage());
            return Optional.empty();
        }
    }

    private void injectBot() {
        log.info("Загружаю модули bot.js в страницу игры ({})", botJsSource());
        page.evaluate("(src) => { (0, eval)(src); }", readBotJs());
        if (!isBotLoaded()) {
            throw new IllegalStateException("Не удалось загрузить bot.js: window.bot не появился");
        }
    }

    /**
     * Читает модули bot.js по порядку из manifest.txt и склеивает их через ";\n".
     * Если задано {@code wow.bot-js-dir}, модули читаются с диска при каждой загрузке
     * (пересборка не нужна), иначе — из classpath.
     */
    private String readBotJs() {
        String dir = properties.botJsDir();
        List<String> modules = readBotModules(dir);

        StringBuilder js = new StringBuilder();
        for (String module : modules) {
            String source = readBotModule(dir, module).stripTrailing();
            if (source.isEmpty()) {
                continue;
            }
            if (!js.isEmpty()) {
                js.append(BOT_MODULE_JOINER);
            }
            js.append(source);
        }
        return js.toString();
    }

    /** Список модулей из manifest.txt: по одному имени на строку, # — комментарий. */
    private static List<String> readBotModules(String dir) {
        String manifestPath = dir == null ? BOT_MANIFEST_RESOURCE : dir + "/manifest.txt";
        String manifest = dir == null
                ? readResource(BOT_MANIFEST_RESOURCE)
                : readFile(Path.of(dir, "manifest.txt"));

        List<String> modules = manifest.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (modules.isEmpty()) {
            throw new IllegalStateException("Манифест модулей бота пуст: " + manifestPath);
        }
        return modules;
    }

    private static String readBotModule(String dir, String module) {
        if (module.contains("..") || module.contains("/") || module.contains("\\")) {
            throw new IllegalStateException("Недопустимое имя модуля в manifest.txt: " + module);
        }
        return dir == null
                ? readResource(BOT_MODULE_PREFIX + module)
                : readFile(Path.of(dir, module));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + path, e);
        }
    }

    private boolean isAddrUnset() {
        return Boolean.TRUE.equals(page.evaluate(
                "typeof window.bot !== 'undefined' && window.bot.addr === null"));
    }

    /** GetTime() из игры должен тикать; если нет — интерфейс перезагрузили. */
    private boolean isStateTimeAlive(long deadline) {
        long windowMs = Math.min(STATE_TIME_WINDOW_MS, remainingMs(deadline));
        if (windowMs <= 0) {
            return false;
        }
        double before = stateTime();
        sleep(windowMs);
        double after = stateTime();
        return before > 0 && after != before;
    }

    private double stateTime() {
        Object value = page.evaluate("() => window.bot.state().time");
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    /** Безопасное чтение bot.state().time (0, если таблица ещё не найдена). */
    private double safeStateTime() {
        try {
            if (page == null || page.isClosed() || isAddrUnset()) {
                return 0d;
            }
            return stateTime();
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    /**
     * Вводит Lua-строки в чат и ищет таблицу. Ни один шаг не ждёт дольше
     * {@code attemptDeadline}: если игра зависла или вкладка заморожена,
     * бросаем {@link #READY_TIMEOUT_MESSAGE}, а не висим.
     */
    private void runLuaSetup(long attemptDeadline) {
        log.info("Выполняю Lua-подготовку ({} строк)", LUA_SETUP.size());
        page.evaluate("() => { const c = document.getElementById('canvas'); if (c) c.focus(); }");
        for (String line : LUA_SETUP) {
            long remaining = remainingMs(attemptDeadline);
            if (remaining <= 0) {
                throw readyTimeout();
            }
            page.evaluate("t => chat(t)", line);
            sleep(Math.min(LUA_LINE_PAUSE_MS, remainingMs(attemptDeadline)));
        }
        long remaining = remainingMs(attemptDeadline);
        if (remaining <= 0) {
            throw readyTimeout();
        }
        // Playwright дождётся промиса, который вернёт find(); сам find ограничен
        // таймаутом, чтобы не зависнуть, если таймеры страницы заморожены.
        // Важно: аргумент — int, а не long: Playwright умеет передавать в страницу
        // только Integer/Double, на Long падает «Unsupported type of argument».
        Object result = page.evaluate(FIND_WITH_TIMEOUT_JS, toPageTimeoutMs(remaining));
        if ("timeout".equals(result)) {
            throw readyTimeout();
        }
        if (!"ok".equals(result)) {
            throw new IllegalStateException("bot.find() завершился с ошибкой: " + result);
        }
        if (isAddrUnset()) {
            throw new IllegalStateException(LUA_NOT_FOUND_MESSAGE);
        }
        log.info("Бот готов: {}", page.evaluate("() => JSON.stringify(bot.state())"));
    }

    private Object evaluate(String expression) {
        return page.evaluate(expression);
    }

    // ------------------------------------------------------------------
    // Инфраструктура
    // ------------------------------------------------------------------

    private <T> T onPlaywrightThread(Callable<T> task) {
        return onPlaywrightThread(0, task);
    }

    /**
     * Выполняет задачу в потоке Playwright. {@code timeoutMs > 0} — жёсткий предел
     * ожидания: если игра не отвечает (вкладка заморожена), не висим, а прерываем задачу
     * и возвращаем понятную ошибку.
     */
    private <T> T onPlaywrightThread(long timeoutMs, Callable<T> task) {
        Future<T> future = playwrightThread.submit(task);
        try {
            return timeoutMs > 0
                    ? future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Операция в игре не уложилась в {} мс — прерываю и переподключаюсь", timeoutMs);
            forgetPage();
            throw new IllegalStateException(NO_RESPONSE_MESSAGE, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Операция прервана", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Операция прервана", e);
        }
    }

    private static String readResource(String name) {
        try (InputStream in = GameBridge.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Не найден ресурс " + name + " в classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать ресурс " + name, e);
        }
    }

    private static List<String> readResourceLines(String name) {
        return readResource(name).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** Модули Lua-подготовки + строка с версией (чтобы Java могла проверить, что таблица в игре актуальна). */
    private static List<String> buildLuaSetup() {
        List<String> lines = new ArrayList<>(readResourceLines("lua-setup.txt"));
        lines.add("/run B[30]=" + LUA_VERSION);
        return List.copyOf(lines);
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            onPlaywrightThread(() -> {
                forgetPage();
                if (playwright != null) {
                    // Закрывает только драйвер Playwright, Chrome пользователя остаётся открытым.
                    playwright.close();
                    playwright = null;
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("Ошибка при остановке Playwright: {}", e.getMessage());
        } finally {
            playwrightThread.shutdownNow();
        }
    }
}
