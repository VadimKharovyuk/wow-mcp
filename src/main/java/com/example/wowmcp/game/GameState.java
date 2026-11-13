package com.example.wowmcp.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Снимок состояния персонажа — то, что возвращает {@code bot.report()} из bot.js.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameState(
        Job job,
        int hpPct,
        double x,
        double y,
        double facing,
        double hp,
        double hpMax,
        boolean fight,
        double time,
        boolean target,
        double targetHp,
        boolean enemy) {

    /**
     * Текущая задача бота. У завершённой задачи {@code result} содержит итог,
     * у упавшей — {@code error}. В JSON могут быть и другие поля (состояние персонажа),
     * их игнорируем.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(String name, String status, Long started, String result, String error) {
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public static GameState parse(String json) {
        return JSON.readValue(json, GameState.class);
    }
}
