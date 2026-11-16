package com.example.wowmcp.telegram;

import com.example.wowmcp.db.LeaderLock;
import com.example.wowmcp.telegram.CommandRouter.Command;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telegram-бот управления: принимает команды только от разрешённых чатов (allowed-chat-ids), выполняет их через
 * {@link CommandExecutor} и шлёт уведомления ({@link #broadcast}). Опрос (long polling) ведёт только процесс-лидер
 * ({@link LeaderLock}): иначе два процесса дрались бы за getUpdates и дублировали ответы.
 *
 * <p>Пока не задан токен, бот выключен. Команды, накопившиеся, пока бот был выключен, при старте отбрасываются
 * (старый «качайся» не должен выполниться через час).
 */
@Component
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private static final int POLL_SECONDS = 25;
    private static final long UNAUTHORIZED_REPLY_GAP_MS = 10 * 60 * 1000;

    /** Меню «/»: команда (латиницей) и описание. Эти слэш-команды разбирает {@link CommandRouter}. */
    static final List<String[]> MENU = List.of(
            new String[]{"status", "Статус персонажа"},
            new String[]{"stop", "Остановить всё"},
            new String[]{"grind", "Качаться вокруг места (N убийств / M минут)"},
            new String[]{"patrol", "Качаться по точкам"},
            new String[]{"hunt", "Выбрать место охоты и качаться"},
            new String[]{"errors", "Ошибки бота (2 часа, лут, паладин)"},
            new String[]{"logs", "Журнал бота (все события)"},
            new String[]{"bags", "Сумки и деньги"},
            new String[]{"sell", "Продать хлам и починиться"},
            new String[]{"revive", "Воскреснуть"},
            new String[]{"mount", "Сесть на коня"},
            new String[]{"dismount", "Слезть с коня"},
            new String[]{"pet", "Призвать питомца"},
            new String[]{"rest", "Отдохнуть (HP и мана)"},
            new String[]{"roadslist", "Список дорог"},
            new String[]{"road", "Дорога N: ехать и пройти"},
            new String[]{"places", "Мои места"},
            new String[]{"routes", "Маршруты"},
            new String[]{"spells", "Умения на панели"},
            new String[]{"rotation", "Ротация"},
            new String[]{"reload", "Перезагрузить бота"},
            new String[]{"help", "Список команд"});

    /** Постоянная клавиатура внизу чата. */
    static final Map<String, Object> KEYBOARD = Map.of(
            "keyboard", List.of(
                    List.of(Map.of("text", "Статус"), Map.of("text", "Качайся"), Map.of("text", "Стоп")),
                    List.of(Map.of("text", "Иди качайся"), Map.of("text", "Продай"), Map.of("text", "Воскресни")),
                    List.of(Map.of("text", "Сумки"), Map.of("text", "Места"), Map.of("text", "Помощь")),
                    List.of(Map.of("text", "Ошибки"), Map.of("text", "Логи"))),
            "resize_keyboard", true);

    private final TelegramProperties props;
    private final LeaderLock leader;
    private final CommandExecutor executor;
    private volatile TelegramApi api;
    private volatile boolean running;
    private Thread poller;
    private final ExecutorService commandPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wow-telegram-cmd");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Long> lastUnauthorizedReply = new java.util.concurrent.ConcurrentHashMap<>();

    public TelegramBot(TelegramProperties props, LeaderLock leader, CommandExecutor executor) {
        this.props = props;
        this.leader = leader;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.configured()) {
            log.info("Telegram не настроен (wow.telegram.token пуст) — бот выключен");
            return;
        }
        api = new TelegramApi(props.apiUrl(), props.token());
        running = true;
        // Поток опроса — демон: процесс MCP должен завершаться вместе с OpenCode. Контроллер удерживает JVM сам.
        poller = new Thread(this::pollLoop, "wow-telegram-poll");
        poller.setDaemon(true);
        poller.start();
        log.info("Telegram: бот запущен, разрешённых чатов: {}", props.allowedChatIds().size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
        commandPool.shutdownNow();
    }

    /** Для тестов: подставить клиент без запуска опроса. */
    void attach(TelegramApi testApi) {
        this.api = testApi;
    }

    /** Разослать текст всем разрешённым чатам. Только лидер и только если бот настроен. */
    public void broadcast(String text) {
        TelegramApi a = api;
        if (a == null || text == null || !leader.isLeader()) return;
        for (long chat : props.allowedChatIds()) {
            try {
                a.sendMessage(chat, text, null);
            } catch (Exception e) {
                log.warn("Telegram: уведомление не отправлено: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Опрос
    // ------------------------------------------------------------------

    private void pollLoop() {
        long offset = 0;
        boolean wasLeader = false;
        long backoffMs = 2000;
        while (running) {
            try {
                if (!leader.isLeader()) {
                    wasLeader = false;
                    Thread.sleep(5000);
                    continue;
                }
                if (!wasLeader) {
                    offset = skipPending(api);
                    wasLeader = true;
                    log.info("Telegram: этот процесс опрашивает бота");
                    try {
                        api.setMyCommands(MENU);
                    } catch (Exception e) {
                        log.warn("Telegram: меню команд не обновлено: {}", e.getMessage());   // не мешает работе
                    }
                    broadcast("🟢 Контроллер запущен. Напишите «помощь».");
                }
                List<JsonNode> updates = api.getUpdates(offset, POLL_SECONDS);
                for (JsonNode u : updates) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    handleUpdate(u);
                }
                backoffMs = 2000;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (TelegramApi.ApiException e) {
                if (e.code() == 401) {
                    log.error("Telegram: токен отклонён (401) — бот остановлен. Проверьте wow.telegram.token");
                    running = false;
                    return;
                }
                log.warn("Telegram: ошибка API {}: {}", e.code(), e.getMessage());
                backoffMs = pause(backoffMs);
            } catch (Exception e) {
                log.warn("Telegram: сбой опроса: {}", e.getMessage());
                backoffMs = pause(backoffMs);
            }
        }
    }

    private long pause(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.min(backoffMs * 2, 60_000);
    }

    /** Отбросить накопившиеся обновления; возвращает offset, с которого читать дальше. */
    long skipPending(TelegramApi a) throws Exception {
        long offset = 0;
        for (JsonNode u : a.getUpdates(0, 0)) {
            offset = Math.max(offset, u.path("update_id").asLong() + 1);
        }
        return offset;
    }

    // ------------------------------------------------------------------
    // Обработка сообщения
    // ------------------------------------------------------------------

    void handleUpdate(JsonNode update) {
        JsonNode msg = update.path("message");
        String text = msg.path("text").asText("");
        long chat = msg.path("chat").path("id").asLong(0);
        if (chat == 0 || text.isBlank()) return;
        Command cmd = CommandRouter.parse(text);

        if ("id".equals(cmd.name())) {                       // chat id нужен, чтобы вписать его в настройки
            reply(chat, "Ваш chat id: " + chat + (props.isAllowed(chat) ? "\nДоступ есть." :
                    "\nДоступа нет. Добавьте этот id в wow.telegram.allowed-chat-ids и перезапустите контроллер."));
            return;
        }
        if (!props.isAllowed(chat)) {
            // Чужим не отвечаем (иначе бот — открытая дверь для спама), кроме «/start» — раз в 10 минут
            long now = System.currentTimeMillis();
            if ("help".equals(cmd.name()) && now - lastUnauthorizedReply.getOrDefault(chat, 0L) > UNAUTHORIZED_REPLY_GAP_MS) {
                lastUnauthorizedReply.put(chat, now);
                reply(chat, "Доступа нет. Напишите /id и добавьте полученный номер в настройки бота.");
            }
            log.warn("Telegram: команда от неразрешённого чата {} проигнорирована", chat);
            return;
        }
        if (CommandRouter.UNKNOWN.equals(cmd.name())) {
            reply(chat, "Не понял команду. Напишите «помощь».");
            return;
        }
        // Команды ждут игру (до минуты и больше) — не блокируем опрос, «стоп» должен проходить сразу
        commandPool.submit(() -> reply(chat, executor.execute(cmd)));
    }

    private void reply(long chat, String text) {
        try {
            api.sendMessage(chat, text, KEYBOARD);
        } catch (Exception e) {
            log.warn("Telegram: ответ не отправлен: {}", e.getMessage());
        }
    }
}
