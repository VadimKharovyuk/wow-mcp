package com.example.wowmcp.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.DeserializationFeature;
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
        double targetHpMax,
        double targetHpPct,
        boolean enemy,
        long ready,
        int err,
        double errTime,
        boolean moDead,
        int lootN,
        int tLvl,
        int tFlags,
        int tDist,
        double manaPct,
        Object grind,
        boolean moAlive,
        int moLvl,
        int kills,
        int zone,
        int pLvl,
        boolean dead,
        boolean ghost) {

    /**
     * Текущая задача бота. У завершённой задачи {@code result} содержит итог,
     * у упавшей — {@code error}. В JSON могут быть и другие поля (состояние персонажа),
     * их игнорируем.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(
            String name,
            String status,
            Long started,
            String result,
            String error,
            long ready,
            int err,
            double errTime,
            boolean moDead,
            int lootN) {
    }

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static GameState parse(String json) {
        return JSON.readValue(json, GameState.class);
    }
}
