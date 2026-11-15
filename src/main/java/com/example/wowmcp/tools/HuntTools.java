package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.HuntRepository;
import com.example.wowmcp.db.VendorRepository;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.JSON;
import static com.example.wowmcp.tools.ToolSupport.jsonResult;
import static com.example.wowmcp.tools.ToolSupport.vendorArg;

/** MCP-инструмент {@code hunt}: «иди качайся» одной командой — выбор лучшего места охоты в зоне и гринд там. */
@Component
public class HuntTools {

    private static final int CLUSTERS_PASSED = 8;
    /** Радиус поиска имён мобов вокруг центра кластера, единицы карты (клетка кластера 3×3). */
    private static final double CLUSTER_NAMES_RADIUS = 4.0;

    private final GameBridge bridge;
    private final Database db;
    private final HuntRepository hunts;
    private final VendorRepository vendors;

    public HuntTools(GameBridge bridge, Database db, HuntRepository hunts, VendorRepository vendors) {
        this.bridge = bridge;
        this.db = db;
        this.hunts = hunts;
        this.vendors = vendors;
    }

    @Tool(name = "hunt", description = """
            Охота одной командой: выбирает в текущей зоне лучшее место охоты под уровень (по базе мест появления мобов), \
            бежит туда (с боем по пути), охотится там (grind, radius 3 ед. карты). Если 60 с нет новой цели — \
            место считается пустым (на 15 мин), недоступное — на 60 мин, и бот идёт к следующему. \
            Работает в фоне — результат и статистика (поле hunt) через wait_job / get_state. \
            Все расстояния — в единицах карты зоны (не метры), 1 ед. ≈ 50 м в небольшой зоне. \
            Только текущая зона; зона должна быть в zone_map.""")
    public String hunt(
            @ToolParam(required = false, description = "Сколько убить, 1..200 (по умолчанию 30)") Integer kills,
            @ToolParam(required = false, description = "Собирать добычу, true/false (по умолчанию false)") Boolean loot,
            @ToolParam(required = false, description = "Максимальное расстояние до места охоты в ЕДИНИЦАХ КАРТЫ (не метры), 2..60 (по умолчанию 25)")
            Double maxDistance) {
        int killTarget = kills == null ? 30 : kills;
        if (killTarget < 1 || killTarget > 200) {
            throw new IllegalArgumentException("kills должен быть от 1 до 200");
        }
        double maxDist = maxDistance == null ? 25.0 : maxDistance;
        if (!Double.isFinite(maxDist) || maxDist < 2 || maxDist > 60) {
            throw new IllegalArgumentException("maxDistance должен быть от 2 до 60 (единицы карты, не метры)");
        }
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — hunt недоступен");
        }
        GameState state = GameState.parse(bridge.report());
        Integer zoneId = hunts.zoneId(state.zone());
        if (zoneId == null) {
            return jsonResult("зона " + state.zone() + " не известна, добавь в zone_map");
        }
        List<Map<String, Object>> clusters = hunts.bestClusters(zoneId, state.pLvl(), state.x(), state.y(), maxDist, CLUSTERS_PASSED);
        if (clusters.isEmpty()) {
            return jsonResult("подходящих мест охоты нет: уровень " + state.pLvl() + ", расстояние ≤ " + maxDist
                    + " (возможно, все помечены пустыми или в zone_map/mob_cluster нет данных)");
        }
        List<Map<String, Object>> forPage = new ArrayList<>();
        for (Map<String, Object> c : clusters) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cx", c.get("cx"));
            p.put("cy", c.get("cy"));
            p.put("spawns", c.get("spawns"));
            p.put("mobs", c.get("mobs"));
            p.put("names", hunts.namesAround(zoneId, state.pLvl(), (Double) c.get("cx"), (Double) c.get("cy"), CLUSTER_NAMES_RADIUS));
            forPage.add(p);
        }
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("kills", killTarget);
        opts.put("loot", loot != null && loot);
        opts.put("zoneId", zoneId);
        opts.put("clusters", forPage);
        Map<String, Object> vendor = vendors.nearestVendor(state.zone(), state.x(), state.y());
        if (vendor != null) {
            opts.put("vendor", vendorArg(vendor));
        }
        String started = bridge.start("hunt", List.of(opts));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", started);
        response.put("zone", state.zone());
        response.put("zoneId", zoneId);
        response.put("clusters", clusters.stream().map(c -> Map.of(
                "x", c.get("cx"), "y", c.get("cy"), "spawns", c.get("spawns"),
                "distance", c.get("distance"), "score", Math.round((Double) c.get("score") * 10) / 10.0)).toList());
        return JSON.writeValueAsString(response);
    }
}
