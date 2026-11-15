package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import com.example.wowmcp.roads.RoadService;
import com.example.wowmcp.roads.RoadService.Plan;
import com.example.wowmcp.roads.RoadService.Waypoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.wowmcp.tools.ToolSupport.JSON;

/** Запуск движения к точке: по графу дорог (если он помогает), иначе прямо — как раньше. */
@Component
public class Navigator {

    private final GameBridge bridge;
    private final Database db;
    private final RoadService roads;

    public Navigator(GameBridge bridge, Database db, RoadService roads) {
        this.bridge = bridge;
        this.db = db;
        this.roads = roads;
    }

    /** go_to (fight = true: отбиваться, собирать добычу) или move_to (fight = false: без боя). */
    public String start(double x, double y, boolean fight, boolean autoLoot, boolean useRoads) {
        Optional<Plan> plan = Optional.empty();
        GameState state = null;
        if (useRoads && db.isEnabled()) {
            state = GameState.parse(bridge.report());
            if (!state.dead() && !state.ghost()) {
                plan = roads.plan(state.zone(), state.x(), state.y(), x, y);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (plan.isPresent()) {
            Plan p = plan.get();
            Map<String, Object> opts = new LinkedHashMap<>();
            opts.put("name", "по дорогам");
            opts.put("fight", fight);
            opts.put("loot", fight && autoLoot);
            opts.put("tolMid", 0.3);
            opts.put("zone", state.zone());
            opts.put("roads", true);
            response.put("result", bridge.start("goRoute", List.of(pointsArg(p.points()), opts)));
            response.put("roads", Map.of("nodes", p.nodes(),
                    "roadLength", Math.round(p.roadLength() * 10) / 10.0,
                    "directLength", Math.round(p.directLength() * 10) / 10.0));
        } else if (fight) {
            response.put("result", bridge.start("go", List.of(x, y, Map.of("autoLoot", autoLoot))));
        } else {
            response.put("result", bridge.start("moveTo", List.of(x, y)));
        }
        return JSON.writeValueAsString(response);
    }

    /** Только double/boolean: Playwright не принимает float/BigDecimal. */
    private static List<Map<String, Object>> pointsArg(List<Waypoint> pts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Waypoint w : pts) out.add(Map.of("x", w.x(), "y", w.y(), "node", w.node()));
        return out;
    }
}
