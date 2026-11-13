package com.example.wowmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки подключения к игре.
 *
 * @param cdpUrl          адрес Chrome DevTools Protocol (Chrome должен быть запущен с --remote-debugging-port)
 * @param pageUrlContains часть URL вкладки, в которой открыта игра
 * @param botJsPath       путь к bot.js на диске; если не задан, скрипт берётся из classpath
 */
@ConfigurationProperties(prefix = "wow")
public record WowProperties(String cdpUrl, String pageUrlContains, String botJsPath) {

    public WowProperties {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            cdpUrl = "http://localhost:9222";
        }
        if (pageUrlContains == null || pageUrlContains.isBlank()) {
            pageUrlContains = "lasthavens.com/play";
        }
        if (botJsPath != null && botJsPath.isBlank()) {
            botJsPath = null;
        }
    }
}
