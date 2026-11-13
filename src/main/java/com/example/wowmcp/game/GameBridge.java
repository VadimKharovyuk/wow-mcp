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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final List<String> LUA_SETUP = readResourceLines("lua-setup.txt");

    private static final long LUA_LINE_PAUSE_MS = 700;
    private static final long STATE_TIME_WINDOW_MS = 1000;
    /** Пауза после bot.stop() перед удалением бота: чтобы клавиши успели отпуститься. */
    private static final long RELOAD_RELEASE_KEYS_PAUSE_MS = 200;

    private static final String LUA_NOT_FOUND_MESSAGE =
            "Lua-таблица не найдена: проверь, что персонаж в игре и чат не занят";

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

    public GameBridge(WowProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Публичный API для инструментов MCP
    // ------------------------------------------------------------------

    /** Сырой JSON от {@code bot.report()} с предварительной проверкой готовности бота. */
    public String report() {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> {
            ensureReadyInternal();
            return (String) evaluate("() => bot.report()");
        }));
    }

    /** {@code bot.report()} без проверки готовности — для частого опроса в wait_job. */
    public String reportRaw() {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> (String) evaluate("() => bot.report()")));
    }

    /** Запускает фоновую задачу бота ({@code bot.start(name, ...args)}). */
    public String start(String name, List<?> args) {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> {
            ensureReadyInternal();
            Object result = page.evaluate("(p) => bot.start(p.name, ...p.args)",
                    Map.of("name", name, "args", args));
            return String.valueOf(result);
        }));
    }

    /** Экстренная остановка: не ждём Lua-подготовки, просто гасим бота, если он загружен. */
    public String stop() {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> (String) evaluate(
                "() => { if (window.bot) { bot.stop(); return 'остановлено'; } "
                        + "return 'бот не загружен — останавливать нечего'; }")));
    }

    /** Проверяет, что bot.js загружен, Lua-таблица найдена и игра не перезагружалась. */
    public void ensureReady() {
        onPlaywrightThread(() -> retryOnDisconnect(() -> {
            ensureReadyInternal();
            return null;
        }));
    }

    /**
     * Перезагружает bot.js в странице игры без перезагрузки самой страницы.
     *
     * <p>Останавливает бота (чтобы отпустились клавиши), удаляет {@code window.bot}
     * и снова проходит обычную подготовку: скрипт вставляется заново, затем выполняется
     * Lua-подготовка и поиск таблицы.
     */
    public String reloadBot() {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> {
            log.info("Перезагружаю bot.js, старый бот останавливается");
            page.evaluate("() => { if (window.bot) window.bot.stop(); }");
            sleep(RELOAD_RELEASE_KEYS_PAUSE_MS);
            page.evaluate("() => { delete window.bot; }");
            ensureReadyInternal();
            return "бот перезагружен: " + botJsSource();
        }));
    }

    /** Откуда берётся bot.js — для отчёта reload_bot. */
    private String botJsSource() {
        String dir = properties.botJsDir();
        return dir == null ? "из classpath (bot/manifest.txt)" : "с диска " + dir;
    }

    /** Произвольное выражение/функция в контексте страницы игры. */
    public Object eval(String js) {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> page.evaluate(js)));
    }

    /** Вызов функции с аргументом — Playwright сам сериализует его в JS. */
    public Object eval(String fn, Object arg) {
        return onPlaywrightThread(() -> retryOnDisconnect(() -> page.evaluate(fn, arg)));
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
            log.warn("Соединение с игрой упало ({}). Переподключаюсь и повторяю.", e.getMessage());
            forgetPage();
            ensureConnected();
            return action.get();
        }
    }

    // ------------------------------------------------------------------
    // Подготовка бота
    // ------------------------------------------------------------------

    private void ensureReadyInternal() {
        if (!isBotLoaded()) {
            injectBot();
        }
        if (isAddrUnset()) {
            runLuaSetup();
        } else if (!isStateTimeAlive()) {
            log.warn("bot.state().time не меняется — похоже, интерфейс игры перезагружался. "
                    + "Повторяю Lua-подготовку.");
            page.evaluate("() => { window.bot.addr = null; }");
            runLuaSetup();
        }
    }

    private boolean isBotLoaded() {
        return Boolean.TRUE.equals(page.evaluate("typeof window.bot !== 'undefined'"));
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
    private boolean isStateTimeAlive() {
        double before = stateTime();
        sleep(STATE_TIME_WINDOW_MS);
        double after = stateTime();
        return before > 0 && after != before;
    }

    private double stateTime() {
        Object value = page.evaluate("() => window.bot.state().time");
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private void runLuaSetup() {
        log.info("Выполняю Lua-подготовку ({} строк)", LUA_SETUP.size());
        page.evaluate("() => { const c = document.getElementById('canvas'); if (c) c.focus(); }");
        for (String line : LUA_SETUP) {
            page.evaluate("t => chat(t)", line);
            sleep(LUA_LINE_PAUSE_MS);
        }
        // Playwright дождётся промиса, который вернёт find().
        page.evaluate("() => bot.find()");
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
        try {
            return playwrightThread.submit(task).get();
        } catch (InterruptedException e) {
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
