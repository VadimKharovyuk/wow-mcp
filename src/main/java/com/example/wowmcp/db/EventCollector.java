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

    /** Накопители для минутного лога «события: cast=N, kill=M, вставлено cast=K». */
    private final java.util.concurrent.atomic.AtomicInteger statCasts = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statKills = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statCastsInserted = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statKeys = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statDrains = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statInsertErrors = new java.util.concurrent.atomic.AtomicInteger();

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
        statDrains.incrementAndGet();
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
                // key_press больше не наполняем: нажатия дублируют spell_cast и путают обучение
                keys++;
                statKeys.incrementAndGet();
            } else if ("cast".equals(type)) {
                casts++;
                statCasts.incrementAndGet();
                if (db.isEnabled()) {
                    String profile = str(ev.get("profile"));
                    if (profile == null || profile.isBlank()) {
                        profile = "default";   // в БД profile NOT NULL — не теряем событие
                    }
                    lastProfile = profile;
                    Integer nameHash = asInt(ev.get("spellId")); // JS кладёт spellId = nameHash
                    Integer spellId = asInt(ev.get("nameHash"));  // запасной вариант, если пришёл отдельно
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
                    boolean inserted = db.insertSpellCast(profile, nameHash == null ? 0 : nameHash, spellId, hp, mana,
                            fight, target, targetHpPct, readySpells, sinceFightMs, prevSpell, prevSpellMs,
                            at == 0 ? System.currentTimeMillis() : at);
                    if (inserted) {
                        statCastsInserted.incrementAndGet();
                    } else {
                        statInsertErrors.incrementAndGet();
                    }
                }
            } else if ("kill".equals(type)) {
                kills++;
                statKills.incrementAndGet();
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

        // Автообучение: если профиль активен и накопилось >=40 новых применений
        if (db.isEnabled() && lastProfile != null) {
            try {
                String activeProfile = String.valueOf(bridge.eval("() => bot.profile"));
                if (lastProfile.equals(activeProfile)) {
                    List<Map<String, Object>> spellRows = db.querySpellCasts(lastProfile);
                    if (!spellRows.isEmpty()) {
                        if (spellRows.size() - learner.lastLearnedCount(lastProfile) >= 40) {
                            List<Map<String, Object>> currentRotation = List.of();
                            try {
                                Object rawRot = bridge.eval("() => JSON.stringify(bot.rotation)");
                                currentRotation = json.readValue(String.valueOf(rawRot), new TypeReference<>() {});
                            } catch (Exception ignored) {
                            }
                            RotationLearner.SpellLearn attempt = learner.learnSpells(lastProfile, currentRotation);
                            if (!attempt.isInsufficient() && attempt.result() != null) {
                                RotationLearner.Result res = attempt.result();
                                String rotJson = json.writeValueAsString(res.rotation());
                                bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
                                String statsJson = res.stats() == null ? null : json.writeValueAsString(res.stats());
                                db.upsertRotationProfile(lastProfile, rotJson, res.samples(), statsJson);
                                log.info("Автообучение профиля '{}': {}", lastProfile, res.rotation());
                            }
                        }
                    } else {
                        int total = db.queryKeyPresses(lastProfile).size();
                        if (total - learner.lastLearnedCount(lastProfile) >= 40) {
                            RotationLearner.Result res = learner.learn(lastProfile);
                            if (res != null) {
                                String rotJson = json.writeValueAsString(res.rotation());
                                bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
                                String statsJson = res.stats() == null ? null : json.writeValueAsString(res.stats());
                                db.upsertRotationProfile(lastProfile, rotJson, res.samples(), statsJson);
                                log.info("Автообучение профиля '{}': {}", lastProfile, res.rotation());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Автообучение не удалось: {}", e.getMessage());
            }
        }
    }

    /**
     * Раз в минуту: даже когда событий нет, видно, что сборщик жив и БД доступна.
     * Числа — за прошедшую минуту.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void logStats() {
        log.info("события: cast={}, kill={}, вставлено cast={} (key={}, опросов страницы={}, ошибок вставки={}, БД {})",
                statCasts.getAndSet(0), statKills.getAndSet(0), statCastsInserted.getAndSet(0),
                statKeys.getAndSet(0), statDrains.getAndSet(0), statInsertErrors.getAndSet(0),
                db.isEnabled() ? "включена" : "выключена");
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
