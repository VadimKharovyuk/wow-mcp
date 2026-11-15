package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.roads.RoadService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.JSON;
import static com.example.wowmcp.tools.ToolSupport.jsonResult;

/** MCP-инструмент графа дорог. */
@Component
public class RoadTools {

    private final Database db;
    private final RoadService roads;

    public RoadTools(Database db, RoadService roads) {
        this.db = db;
        this.roads = roads;
    }

    @Tool(name = "build_roads", description = """
            Перестроить граф дорог по всем записанным путям (record_start/stop — целиком, путь игрока — целиком, \
            путь бота — без участков с остановкой > 3 с). Счётчики застреваний и выученные рёбра сохраняются. \
            После этого go_to и move_to идут по дорогам, если это помогает (useRoads=true по умолчанию). \
            Возвращает число узлов и рёбер по зонам.""")
    public String buildRoads() {
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — граф дорог недоступен");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zones", roads.build());
        return JSON.writeValueAsString(result);
    }
}
