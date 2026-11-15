package com.example.wowmcp.db;

import java.util.Map;

/** Событие игры для уведомлений (смерть, конец задачи бота, продажа). Публикует EventCollector (только лидер). */
public record GameNotification(String type, Map<String, Object> event) {
}
