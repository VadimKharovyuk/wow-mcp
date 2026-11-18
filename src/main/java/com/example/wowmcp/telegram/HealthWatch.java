package com.example.wowmcp.telegram;

import com.example.wowmcp.tools.HealthTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Раз в минуту проверяет серьёзные поломки и пишет в Telegram: база выключилась или сбор событий остановился
 * при загруженном боте. Только лидер (события читает он). Одно и то же сообщение — не чаще раза в 30 минут.
 */
@Component
public class HealthWatch {

    private static final Logger log = LoggerFactory.getLogger(HealthWatch.class);
    private static final long REPEAT_MS = 30 * 60_000L;

    private final HealthTools health;
    private final TelegramBot bot;
    private final Map<String, Long> lastSent = new HashMap<>();

    public HealthWatch(HealthTools health, TelegramBot bot) {
        this.health = health;
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void check() {
        try {
            HealthTools.Snapshot s = health.snapshot();
            if (!s.leader()) return;
            List<String> serious = HealthTools.problems(s).stream()
                    .filter(p -> p.startsWith("❗") || p.startsWith("⚠ Сбор событий не опрашивал")).toList();
            long now = System.currentTimeMillis();
            for (String p : serious) {
                String key = p.replaceAll("\\d+", "#");   // в тексте меняются секунды — ключ без цифр
                Long prev = lastSent.get(key);
                if (prev != null && now - prev < REPEAT_MS) continue;
                lastSent.put(key, now);
                log.warn("Самопроверка: {}", p);
                bot.broadcast("🩺 " + p);
            }
        } catch (RuntimeException e) {
            log.debug("Самопроверка не удалась: {}", e.getMessage());
        }
    }
}
