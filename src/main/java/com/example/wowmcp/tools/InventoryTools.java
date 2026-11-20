package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.KeepItemRepository;
import com.example.wowmcp.db.VendorRepository;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.JSON;
import static com.example.wowmcp.tools.ToolSupport.jsonResult;
import static com.example.wowmcp.tools.ToolSupport.requireNotBlank;
import static com.example.wowmcp.tools.ToolSupport.vendorArg;

/** MCP-инструменты сумок и торговцев: состояние сумок, запоминание торговца, продажа, список KEEP. */
@Component
public class InventoryTools {

    private final GameBridge bridge;
    private final Database db;
    private final VendorRepository vendors;
    private final KeepItemRepository keepItems;

    public InventoryTools(GameBridge bridge, Database db, VendorRepository vendors, KeepItemRepository keepItems) {
        this.bridge = bridge;
        this.db = db;
        this.vendors = vendors;
        this.keepItems = keepItems;
    }

    @Tool(name = "bags", description = """
            Показывает состояние сумок: свободно/всего слотов (обычные сумки), сколько предметов к продаже, \
            деньги, открыто ли окно торговца и т.д.""")
    public String bags() {
        bridge.ensureReady();
        Object raw = bridge.eval("() => JSON.stringify(bot.bags ? bot.bags() : {})");
        try {
            Object parsed = JSON.readValue(String.valueOf(raw), Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> {
                    out.put(String.valueOf(k), v);
                    if ("money".equals(k) && v instanceof Number n)
                        out.put("moneyText", coins(n.longValue()));
                    if ("repairCost".equals(k) && v instanceof Number n)
                        out.put("repairCostText", coins(n.longValue()));
                });
                parsed = out;
            }
            return JSON.writeValueAsString(Map.of("bags", parsed));
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of("bags", raw));
        }
    }

    @Tool(name = "remember_vendor", description = """
            Запомнить торговца в ТЕКУЩЕЙ точке игрока (zone/x/y из get_state). Игрок должен стоять рядом с NPC.""")
    public String rememberVendor(
            @ToolParam(description = "Имя торговца (как в игре, для /target)") String name) {
        requireNotBlank(name, "name");
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — торговец не сохранён"));
        }
        GameState state = GameState.parse(bridge.report());
        // facing нужен, чтобы бот при продаже смотрел туда же, куда игрок при запоминании
        vendors.rememberVendor(state.zone(), state.x(), state.y(), name.trim(), state.facing());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "торговец сохранён");
        result.put("zone", state.zone());
        result.put("x", state.x());
        result.put("y", state.y());
        result.put("facing", state.facing());
        result.put("name", name.trim());
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "vendors", description = "Список торговцев текущей зоны.")
    public String vendors() {
        GameState state = GameState.parse(bridge.report());
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", state.zone(), "vendors", List.of()));
        }
        List<Map<String, Object>> list = vendors.listVendors(state.zone());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", state.zone());
        result.put("vendors", list);
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "sell_now", description = """
            Продать хлам и отремонтироваться у ближайшего известного торговца текущей зоны. \
            Запускается в фоне — результат смотри через wait_job.""")
    public String sellNow() {
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — торговец неизвестен"));
        }
        GameState state = GameState.parse(bridge.report());
        Map<String, Object> v = vendors.nearestVendor(state.zone(), state.x(), state.y());
        if (v == null) {
            return JSON.writeValueAsString(Map.of("result", "торговец в текущей зоне не задан", "zone", state.zone()));
        }
        return jsonResult(bridge.start("sellAt", List.of(vendorArg(v))));
    }

    @Tool(name = "keep_item", description = """
            Добавить предмет в список KEEP (не продавать). Сохраняет в БД и сразу применяет в игре.""")
    public String keepItem(
            @ToolParam(description = "ID предмета (itemId)") Integer itemId) {
        if (itemId == null || itemId <= 0) {
            throw new IllegalArgumentException("itemId должен быть положительным целым");
        }
        if (db.isEnabled()) {
            keepItems.add(itemId);
        }
        bridge.ensureReady();
        try {
            bridge.eval("(id) => chat('/run KEEP['+id+']=true')", itemId);
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of(
                    "result", "сохранено в БД, но не удалось применить в игре: " + e.getMessage(),
                    "itemId", itemId,
                    "dbEnabled", db.isEnabled()));
        }
        return JSON.writeValueAsString(Map.of("result", "предмет добавлен в KEEP", "itemId", itemId, "dbEnabled", db.isEnabled()));
    }

    /** Медь → «12з 3с 50м»; нулевые части пропускаются, 0 → «0м». */
    static String coins(long copper) {
        long g = copper / 10000, s = copper / 100 % 100, c = copper % 100;
        StringBuilder sb = new StringBuilder();
        if (g != 0) sb.append(g).append("з ");
        if (s != 0) sb.append(s).append("с ");
        if (c != 0 || sb.length() == 0) sb.append(c).append("м");
        return sb.toString().trim();
    }
}
