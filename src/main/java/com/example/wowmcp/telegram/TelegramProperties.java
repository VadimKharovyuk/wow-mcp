package com.example.wowmcp.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Настройки Telegram-бота. Токен выдаёт @BotFather; его и ваш chat id кладут в НЕ коммитящийся файл
 * ({@code telegram.local.properties} рядом с jar или {@code ~/.wow-mcp/telegram.properties}) либо в переменные
 * окружения {@code WOW_TELEGRAM_TOKEN} и {@code WOW_TELEGRAM_ALLOWED_CHAT_IDS}.
 *
 * @param token          токен бота; пусто — Telegram выключен
 * @param allowedChatIds chat id, которым разрешено управлять ботом (пусто — отвечает только на /id)
 * @param enabled        выключатель (по умолчанию включён, если задан токен)
 * @param apiUrl         адрес Telegram Bot API (меняют только в тестах)
 */
@ConfigurationProperties(prefix = "wow.telegram")
public record TelegramProperties(String token, List<Long> allowedChatIds, Boolean enabled, String apiUrl) {

    public TelegramProperties {
        if (token != null) token = token.strip();
        if (allowedChatIds == null) allowedChatIds = List.of();
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = "https://api.telegram.org";
    }

    /** Бот включён и токен задан. */
    public boolean configured() {
        return !Boolean.FALSE.equals(enabled) && token != null && !token.isBlank() && !token.startsWith("PUT_");
    }

    public boolean isAllowed(long chatId) {
        return allowedChatIds.contains(chatId);
    }
}
