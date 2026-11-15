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

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Соединение с вкладкой игры в Chrome (Playwright по CDP).
 *
 * <p>Playwright не потокобезопасен, поэтому все обращения к {@link Page} выполняются
 * в одном выделенном потоке {@code wow-playwright}. Подключение ленивое. Класс ничего
 * не знает ни про бота, ни про Lua, ни про БД.
 */
@Component
class PageConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PageConnection.class);

    /** Пауза между строками, вводимыми в чат игры. */
    static final long CHAT_LINE_PAUSE_MS = 300;

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

    PageConnection(WowProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Выполнение в потоке Playwright
    // ------------------------------------------------------------------

    /** Страница игры. Вызывать только из потока Playwright, внутри {@link #call} и аналогов. */
    Page page() {
        return page;
    }

    /** Занята ли страница «тяжёлой» операцией. */
    boolean isBusy() {
        return busy.get() > 0;
    }

    /**
     * Выполняет действие на странице с переподключением при обрыве и пометкой «занято»
     * на время выполнения. {@code timeoutMs > 0} — жёсткий предел ожидания.
     */
    <T> T call(long timeoutMs, Supplier<T> action) {
        busy.incrementAndGet();
        try {
            return callOnce(timeoutMs, () -> retryOnDisconnect(action));
        } finally {
            busy.decrementAndGet();
        }
    }

    /** Как {@link #call(long, Supplier)}, без таймаута. */
    <T> T call(Supplier<T> action) {
        return call(0, action);
    }

    /**
     * Выполняет задачу в потоке Playwright без подключения, переподключения и пометки «занято».
     * {@code timeoutMs > 0} — жёсткий предел ожидания: если игра не отвечает (вкладка заморожена),
     * не висим, а прерываем задачу и возвращаем понятную ошибку.
     */
    <T> T callOnce(long timeoutMs, Callable<T> task) {
        Future<T> future = playwrightThread.submit(task);
        try {
            return timeoutMs > 0
                    ? future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Операция в игре не уложилась в {} мс — прерываю и переподключаюсь", timeoutMs);
            forgetPage();
            throw new IllegalStateException(noResponseMessage(timeoutMs), e);
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

    /** Запускает задачу в потоке Playwright в фоне, не дожидаясь результата. */
    void submit(Runnable task) {
        playwrightThread.submit(task);
    }

    private static String noResponseMessage(long timeoutMs) {
        return "Игра не ответила за " + (timeoutMs / 1000) + " с. "
                + "Игра на паузе или окно Chrome свёрнуто? Разверни окно игры и повтори.";
    }

    // ------------------------------------------------------------------
    // Подключение и переподключение (только в потоке playwrightThread)
    // ------------------------------------------------------------------

    void ensureConnected() {
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
    // Утилиты
    // ------------------------------------------------------------------

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Операция прервана", e);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        try {
            callOnce(0, () -> {
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
