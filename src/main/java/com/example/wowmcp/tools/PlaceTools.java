package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.PlaceRepository;
import com.example.wowmcp.db.RouteRepository;
import com.example.wowmcp.db.RouteRepository.Point;
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
import static com.example.wowmcp.tools.ToolSupport.requireNotBlank;

/** MCP-инструменты именованных мест и маршрутов: «иди к гарпиям», «иди по маршруту ...». */
@Component
public class PlaceTools {

    private final GameBridge bridge;
    private final Database db;
    private final PlaceRepository places;
    private final RouteRepository routes;
    private final Navigator navigator;
    /** Порядок маршрутов из последнего «покажи дороги»: «дорога N» берёт отсюда. */
    private volatile List<String> lastRoads = List.of();

    public PlaceTools(GameBridge bridge, Database db, PlaceRepository places, RouteRepository routes,
                      Navigator navigator) {
        this.bridge = bridge;
        this.db = db;
        this.places = places;
        this.routes = routes;
        this.navigator = navigator;
    }

    @Tool(name = "remember_place", description = """
            Запомнить текущее место игрока (зона и координаты) под именем, например «гарпии». \
            Если имя уже есть — перезаписывается. Имена хранятся в нижнем регистре, «ё» заменяется на «е».""")
    public String rememberPlace(
            @ToolParam(description = "Имя места") String name,
            @ToolParam(required = false, description = "Заметка") String note) {
        requireNotBlank(name, "name");
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — место не сохранено");
        }
        GameState state = GameState.parse(bridge.report());
        String key = Names.normalize(name);
        places.remember(key, state.zone(), state.x(), state.y(), note);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "место сохранено");
        result.put("name", key);
        result.put("zone", state.zone());
        result.put("x", state.x());
        result.put("y", state.y());
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "places", description = "Список мест текущей зоны с расстоянием до игрока (в единицах карты).")
    public String places() {
        GameState state = GameState.parse(bridge.report());
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", state.zone(), "places", List.of()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> p : places.listByZone(state.zone())) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            m.remove("created_at");
            double d = Math.hypot(((Number) p.get("x")).doubleValue() - state.x(), ((Number) p.get("y")).doubleValue() - state.y());
            m.put("distance", Math.round(d * 10) / 10.0);
            list.add(m);
        }
        list.sort((a, b) -> Double.compare((Double) a.get("distance"), (Double) b.get("distance")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", state.zone());
        result.put("places", list);
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "go_place", description = """
            Бежать к месту по имени (с боем по пути). Если есть записанный маршрут, который начинается \
            рядом с игроком (≤ 3 ед.) и кончается рядом с местом (≤ 3 ед.), в любом направлении — идёт по нему, \
            иначе напрямую. Место в другой зоне — ошибка. Работает в фоне — результат смотри через wait_job.""")
    public String goPlace(@ToolParam(description = "Имя места") String name) {
        requireNotBlank(name, "name");
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — места недоступны");
        }
        String key = Names.normalize(name);
        Map<String, Object> place = places.find(key);
        if (place == null) {
            return jsonResult("место «" + key + "» не найдено (список: places)");
        }
        GameState state = GameState.parse(bridge.report());
        int placeZone = ((Number) place.get("zone")).intValue();
        if (placeZone != state.zone()) {
            return jsonResult("место в зоне " + placeZone + ", ты в зоне " + state.zone());
        }
        double tx = ((Number) place.get("x")).doubleValue(), ty = ((Number) place.get("y")).doubleValue();

        for (String[] r : routes.listNames()) {
            List<Point> pts = routes.points(r[1]);
            if (pts.isEmpty() || pts.get(0).zone() != state.zone()) continue;
            List<Point> thin = RoutePlanner.thin(pts);
            Boolean reverse = RoutePlanner.fitsPlace(thin, state.x(), state.y(), tx, ty);
            if (reverse != null) {
                List<Point> seq = RoutePlanner.orient(thin, state.x(), state.y(), reverse);
                String started = bridge.start("goRoute", List.of(pointsArg(seq), Map.of("name", r[0] + " → " + key)));
                return JSON.writeValueAsString(Map.of("result", started, "via", "маршрут " + r[0], "points", seq.size()));
            }
        }
        return navigator.start(tx, ty, true, true, true);
    }

    @Tool(name = "routes", description = "Список записанных маршрутов: имя, зона, начало, конец, длина, число точек.")
    public String routes() {
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "routes", List.of()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (String[] r : routes.listNames()) {
            List<Point> pts = routes.points(r[1]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r[0]);
            if (pts.isEmpty()) {
                m.put("points", 0);
            } else {
                Point a = pts.get(0), b = pts.get(pts.size() - 1);
                m.put("zone", a.zone());
                m.put("start", Map.of("x", round1(a.x()), "y", round1(a.y())));
                m.put("end", Map.of("x", round1(b.x()), "y", round1(b.y())));
                m.put("length", round1(RoutePlanner.length(pts)));
                m.put("points", pts.size());
            }
            list.add(m);
        }
        return JSON.writeValueAsString(Map.of("routes", list));
    }

    @Tool(name = "roads_list", description = """
            Список «дорог» (записанных маршрутов) текущей зоны: номер, откуда → куда (по ближайшим сохранённым местам), \
            длина и расстояние до начала. Выбрать — go_road с номером или именем.""")
    public String roadsList() {
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — дороги недоступны");
        }
        GameState state = GameState.parse(bridge.report());
        List<Map<String, Object>> placeList = places.listByZone(state.zone());
        List<String> order = new ArrayList<>();
        StringBuilder sb = new StringBuilder("Дороги зоны " + state.zone() + ":");
        for (String[] r : routes.listNames()) {
            List<Point> pts = routes.points(r[1]);
            if (pts.isEmpty() || pts.get(0).zone() != state.zone()) continue;
            Point a = pts.get(0), b = pts.get(pts.size() - 1);
            order.add(r[0]);
            sb.append("\n").append(order.size()).append(". ").append(r[0]).append(" — ")
              .append(endName(placeList, a)).append(" → ").append(endName(placeList, b))
              .append(" · длина ").append(round1(RoutePlanner.length(pts)))
              .append(" · до начала ").append(round1(Math.hypot(a.x() - state.x(), a.y() - state.y())));
        }
        lastRoads = List.copyOf(order);
        if (order.isEmpty()) {
            return jsonResult("в этой зоне нет записанных дорог (запиши дорогу → стоп запись ИМЯ)");
        }
        sb.append("\nВыбери: «дорога N» — бот поедет к началу и пройдёт её.");
        return jsonResult(sb.toString());
    }

    @Tool(name = "go_road", description = """
            Пройти дорогу из списка roads_list: по номеру («2») или по имени маршрута. Сначала едет к началу \
            (верхом, если далеко и конь доступен), затем идёт по точкам, в конце слезает. Работает в фоне — wait_job.""")
    public String goRoad(
            @ToolParam(description = "Номер из списка roads_list или имя маршрута") String road,
            @ToolParam(required = false, description = "Идти с конца к началу, true/false (по умолчанию от начала)")
            Boolean reverse) {
        requireNotBlank(road, "road");
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — дороги недоступны");
        }
        String key = road.strip();
        if (key.matches("\\d{1,3}")) {
            int n = Integer.parseInt(key);
            List<String> order = lastRoads;
            if (n < 1 || n > order.size()) {
                return jsonResult("нет дороги №" + n + (order.isEmpty() ? " — сначала «покажи дороги»" : " (есть 1–" + order.size() + ")"));
            }
            key = order.get(n - 1);
        } else {
            key = Names.normalize(key);
        }
        String session = routes.sessionOf(key);
        if (session == null) {
            return jsonResult("дорога «" + key + "» не найдена (список: покажи дороги)");
        }
        List<Point> pts = routes.points(session);
        GameState state = GameState.parse(bridge.report());
        if (pts.isEmpty() || pts.get(0).zone() != state.zone()) {
            return jsonResult("дорога «" + key + "» не в этой зоне");
        }
        List<Point> seq = new ArrayList<>(RoutePlanner.thin(pts));
        if (Boolean.TRUE.equals(reverse)) java.util.Collections.reverse(seq);
        String started = bridge.start("goRoad", List.of(pointsArg(seq), Map.of("name", key)));
        return JSON.writeValueAsString(Map.of("result", started, "route", key, "points", seq.size(),
                "from", Map.of("x", round1(seq.get(0).x()), "y", round1(seq.get(0).y())),
                "to", Map.of("x", round1(seq.get(seq.size() - 1).x()), "y", round1(seq.get(seq.size() - 1).y()))));
    }

    /** Имя места рядом с точкой (в пределах 1 ед.) или координаты. */
    private static String endName(List<Map<String, Object>> placeList, Point p) {
        String best = null;
        double bd = 1.0;
        for (Map<String, Object> pl : placeList) {
            double d = Math.hypot(((Number) pl.get("x")).doubleValue() - p.x(), ((Number) pl.get("y")).doubleValue() - p.y());
            if (d <= bd) { bd = d; best = String.valueOf(pl.get("name")); }
        }
        return best != null ? best : "(" + round1(p.x()) + "; " + round1(p.y()) + ")";
    }

    @Tool(name = "go_route", description = """
            Пройти записанный маршрут по имени. Точки прореживаются (шаг ≈ 1.5 ед., повороты > 25° сохраняются). \
            Направление, если reverse не указан, — «от меня» (конец дальше от игрока); начинает с ближайшей \
            к игроку точки. Застрял на точке — пропускает её; две подряд — остановка. \
            Работает в фоне — результат и «точка N из M» смотри через wait_job / get_state.""")
    public String goRoute(
            @ToolParam(description = "Имя маршрута (см. routes)") String name,
            @ToolParam(required = false, description = "Пройти в обратном направлении, true/false (по умолчанию — от игрока)")
            Boolean reverse) {
        requireNotBlank(name, "name");
        if (!db.isEnabled()) {
            return jsonResult("база не настроена — маршруты недоступны");
        }
        String key = Names.normalize(name);
        String session = routes.sessionOf(key);
        if (session == null) {
            return jsonResult("маршрут «" + key + "» не найден (список: routes)");
        }
        List<Point> pts = routes.points(session);
        if (pts.isEmpty()) {
            return jsonResult("у маршрута «" + key + "» нет точек пути");
        }
        GameState state = GameState.parse(bridge.report());
        if (pts.get(0).zone() != state.zone()) {
            return jsonResult("маршрут в зоне " + pts.get(0).zone() + ", ты в зоне " + state.zone());
        }
        List<Point> seq = RoutePlanner.orient(RoutePlanner.thin(pts), state.x(), state.y(), reverse);
        String started = bridge.start("goRoute", List.of(pointsArg(seq), Map.of("name", key)));
        return JSON.writeValueAsString(Map.of("result", started, "route", key, "points", seq.size(),
                "from", Map.of("x", round1(seq.get(0).x()), "y", round1(seq.get(0).y())),
                "to", Map.of("x", round1(seq.get(seq.size() - 1).x()), "y", round1(seq.get(seq.size() - 1).y()))));
    }

    /** Точки для страницы: только double (Playwright не принимает float/BigDecimal). */
    private static List<Map<String, Object>> pointsArg(List<Point> seq) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Point p : seq) {
            out.add(Map.of("x", p.x(), "y", p.y()));
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
