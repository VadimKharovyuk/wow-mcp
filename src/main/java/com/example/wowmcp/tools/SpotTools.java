package com.example.wowmcp.tools;

import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import com.example.wowmcp.spots.SpotService;
import com.example.wowmcp.spots.SpotStat;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.JSON;

/** MCP-инструмент {@code best_spots}: где качаться лучше всего — по измеренному опыту в час за последние 24 часа. Только чтение. */
@Component
public class SpotTools {

    private final GameBridge bridge;
    private final SpotService spots;

    public SpotTools(GameBridge bridge, SpotService spots) {
        this.bridge = bridge;
        this.spots = spots;
    }

    @Tool(name = "best_spots", description = """
            Лучшие места охоты в текущей зоне по ИЗМЕРЕННОМУ опыту в час за последние 24 часа: опыт считается по снимкам опыта в окнах
            по 10 минут, где работал только бот, со штрафом за смерти и упоры. Показывает клетки карты 2×2 единицы (x, y — центр клетки,
            единицы карты зоны 0..100, не метры), расстояние до каждой, опыт в час, отличие от среднего и надёжность (мало времени в
            клетке — «низкая»). Чтобы пойти туда: go_to на x, y первой клетки, затем grind. Данных нет, пока бот не поработал
            хотя бы несколько окон по 10 минут на текущем уровне.""")
    public String bestSpots(
            @ToolParam(required = false, description = "Сколько клеток показать, 1..10 (по умолчанию 5)") Integer limit,
            @ToolParam(required = false, description = "Зона бота (код); по умолчанию текущая зона персонажа") Integer zone) {
        int n = limit == null ? 5 : Math.max(1, Math.min(10, limit));
        double px = Double.NaN, py = Double.NaN;
        Integer z = zone;
        try {
            GameState st = GameState.parse(bridge.report());
            if (z == null) z = st.zone();
            px = st.x();
            py = st.y();
        } catch (Exception e) {
            // не в игре — зону должен назвать вызывающий
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (z == null) {
            out.put("result", "не знаю зону: войди в игру или передай zone");
            return JSON.writeValueAsString(out);
        }
        List<SpotStat> best = spots.best(z, n);
        out.put("zone", z);
        out.put("level", spots.all().level());
        out.put("averageXpPerHour", spots.all().avgXpPerHour());
        out.put("windows", spots.all().windows());
        if (best.isEmpty()) {
            out.put("result", "для зоны " + z + " пока мало данных: нужны окна по 10 минут, где работал только бот (без твоих убийств), "
                    + "хотя бы 10 убийств и ~10 минут на клетку; поохоться ещё");
            out.put("spots", List.of());
            return JSON.writeValueAsString(out);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpotStat s : best) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("x", s.cx());
            r.put("y", s.cy());
            if (!Double.isNaN(px)) r.put("distance", Math.round(Math.hypot(s.cx() - px, s.cy() - py) * 10) / 10.0);
            r.put("xpPerHour", s.xpPerHour());
            r.put("vsAveragePct", s.vsAveragePct());
            r.put("killsPerHour", s.killsPerHour());
            r.put("hours", s.hours());
            r.put("deaths", s.deaths());
            r.put("stuck", s.stuck());
            r.put("confidence", s.confidence());
            rows.add(r);
        }
        out.put("spots", rows);
        SpotStat top = best.get(0);
        out.put("result", "лучшее место: (" + top.cx() + ", " + top.cy() + ") — " + top.xpPerHour() + " опыта в час ("
                + (top.vsAveragePct() >= 0 ? "+" : "") + top.vsAveragePct() + "% к среднему, надёжность " + top.confidence() + "). "
                + "Пойти: go_to " + top.cx() + " " + top.cy() + ", затем grind.");
        return JSON.writeValueAsString(out);
    }
}
