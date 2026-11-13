package com.example.wowmcp.db;

import com.example.wowmcp.game.GameBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EventCollector {

    private static final Logger log = LoggerFactory.getLogger(EventCollector.class);

    private final GameBridge bridge;
    private final DbService db;
    private final ObjectMapper json = new ObjectMapper();

    public EventCollector(GameBridge bridge, DbService db) {
        this.bridge = bridge;
        this.db = db;
    }

    @Scheduled(fixedDelay = 3000)
    public void collect() {
        if (!bridge.isBotLoadedIfConnected()) {
            return;
        }
        Optional<String> drained = bridge.evalIfReady("bot.drainEvents()", 2000);
        if (drained.isEmpty()) {
            return;
        }
        String payload = drained.get();
        if (payload == null || payload.isBlank() || payload.equals("[]")) {
            return;
        }
        List<Map<String, Object>> events;
        try {
            events = json.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Не удалось разобрать события: {}", e.getMessage());
            return;
        }
        int kills = 0, paths = 0;
        for (Map<String, Object> ev : events) {
            String type = String.valueOf(ev.get("type"));
            if ("kill".equals(type)) {
                kills++;
                if (db.isEnabled()) {
                    Integer zone = asInt(ev.get("zone"));
                    Double x = asDouble(ev.get("x"));
                    Double y = asDouble(ev.get("y"));
                    Integer mobLevel = asInt(ev.get("mobLevel"));
                    Integer playerLevel = asInt(ev.get("playerLevel"));
                    String source = String.valueOf(ev.get("source"));
                    long at = asLong(ev.get("at"));
                    db.insertMobKill(zone == null ? 0 : zone, x == null ? 0 : x, y == null ? 0 : y,
                            mobLevel, playerLevel, source, at == 0 ? System.currentTimeMillis() : at);
                }
            } else if ("path".equals(type)) {
                paths++;
                if (db.isEnabled()) {
                    String sessionId = String.valueOf(ev.get("session"));
                    Integer zone = asInt(ev.get("zone"));
                    Double x = asDouble(ev.get("x"));
                    Double y = asDouble(ev.get("y"));
                    long at = asLong(ev.get("at"));
                    db.insertPathPoint(sessionId, zone == null ? 0 : zone, x == null ? 0 : x,
                            y == null ? 0 : y, at == 0 ? System.currentTimeMillis() : at);
                }
            }
        }
        if (kills + paths > 0) {
            log.debug("Собрано событий: kills={}, paths={}", kills, paths);
        }
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }
}
