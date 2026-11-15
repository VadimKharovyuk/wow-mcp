package com.example.wowmcp.telegram;

import com.example.wowmcp.db.GameNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Уведомления в Telegram о событиях игры: смерть, конец гринда/охоты/маршрута, продажа. События приходят от
 * EventCollector (его запускает только лидер). Одинаковый текст чаще раза в 20 секунд не повторяется.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final long SAME_TEXT_GAP_MS = 20_000;

    private final TelegramBot bot;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public Notifier(TelegramBot bot) {
        this.bot = bot;
    }

    @EventListener
    public void on(GameNotification n) {
        String text = switch (n.type()) {
            case "death" -> MessageFormatter.death(n.event());
            case "job" -> "end".equals(String.valueOf(n.event().get("phase"))) ? MessageFormatter.jobEnd(n.event()) : null;
            case "sell" -> MessageFormatter.sell(n.event());
            default -> null;
        };
        if (text == null) return;
        long now = System.currentTimeMillis();
        Long prev = lastSent.put(text, now);
        if (prev != null && now - prev < SAME_TEXT_GAP_MS) {
            return;
        }
        log.debug("Telegram: уведомление: {}", text);
        bot.broadcast(text);
    }
}
