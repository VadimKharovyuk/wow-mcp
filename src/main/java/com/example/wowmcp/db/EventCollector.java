package com.example.wowmcp.db;

import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.learn.RotationLearner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EventCollector {

    private static final Logger log = LoggerFactory.getLogger(EventCollector.class);

    private final GameBridge bridge;
    private final DbService db;
    private final RotationLearner learner;
    private final ObjectMapper json = new ObjectMapper();

    public EventCollector(GameBridge bridge, DbService db, RotationLearner learner) {
        this.bridge = bridge;
        this.db = db;
        this.learner = learner;
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
        int kills = 0, paths = 0, keys = 0, casts = 0;
        String lastProfile = null;
        for (Map<String, Object> ev : events) {
            String type = String.valueOf(ev.get("type"));
            if ("key".equals(type)) {
                keys++;
                if (db.isEnabled()) {
                    String profile = str(ev.get("profile"));
                    lastProfile = profile;
                    db.insertKeyPress(profile, str(ev.get("key")), asDouble(ev.get("hp")), asDouble(ev.get("mana")),
                            asInt(ev.get("ready")), asBool(ev.get("fight")), asBool(ev.get("target")),
                            asDouble(ev.get("targetHpPct")), asInt(ev.get("sinceFightMs")), str(ev.get("prevKey")),
                            asInt(ev.get("prevKeyMs")), asLong(ev.get("at")));
                }
            } else if ("cast".equals(type)) {
                casts++;
                if (db.isEnabled()) {
                    String profile = str(ev.get("profile"));
                    lastProfile = profile;
                    Integer nameHash = asInt(ev.get("spellId")); // JS кладёт spellId = nameHash
                    Integer spellId = null;
                    Double hp = asDouble(ev.get("hp"));
                    Double mana = asDouble(ev.get("mana"));
                    Boolean fight = asBool(ev.get("fight"));
                    Boolean target = asBool(ev.get("target"));
                    Double targetHpPct = asDouble(ev.get("targetHpPct"));
                    List<Integer> readySpells = new ArrayList<>();
                    Object rs = ev.get("readySpells");
                    if (rs instanceof List<?> list) {
                        for (Object o : list) {
                            Integer v = asInt(o);
                            if (v != null) readySpells.add(v);
                        }
                    }
                    Integer sinceFightMs = asInt(ev.get("sinceFightMs"));
                    Integer prevSpell = asInt(ev.get("prevSpell"));
                    Integer prevSpellMs = asInt(ev.get("prevSpellMs"));
                    long at = asLong(ev.get("at"));
                    db.insertSpellCast(profile, nameHash == null ? 0 : nameHash, spellId, hp, mana, fight, target,
                            targetHpPct, readySpells, sinceFightMs, prevSpell, prevSpellMs,
                            at == 0 ? System.currentTimeMillis() : at);
                }
            } else if ("kill".equals(type)) {
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
        if (kills + paths + keys + casts > 0) {
            log.debug("Собрано событий: kills={}, paths={}, keys={}, casts={}", kills, paths, keys, casts);
        }

        // Автообучение: если профиль активен и накопилось >=40 новых нажатий/применений
        if (db.isEnabled() && lastProfile != null) {
            try {
                String activeProfile = String.valueOf(bridge.eval("() => bot.profile"));
                if (lastProfile.equals(activeProfile)) {
                    int total = db.querySpellCasts(lastProfile).size();
                    if (total == 0) {
                        total = db.queryKeyPresses(lastProfile).size();
                    }
                    if (total - learner.lastLearnedCount(lastProfile) >= 40) {
                        RotationLearner.Result res = db.querySpellCasts(lastProfile).isEmpty()
                                ? learner.learn(lastProfile)
                                : learner.learnSpells(lastProfile);
                        if (res != null) {
                            String rotJson = json.writeValueAsString(res.rotation());
                            bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
                            String statsJson = res.stats() == null ? null : json.writeValueAsString(res.stats());
                            db.upsertRotationProfile(lastProfile, rotJson, res.samples(), statsJson);
                            log.info("Автообучение профиля '{}': {}", lastProfile, res.rotation());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Автообучение не удалось: {}", e.getMessage());
            }
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

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return null;
    }
}
