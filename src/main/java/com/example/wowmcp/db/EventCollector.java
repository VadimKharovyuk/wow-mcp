package com.example.wowmcp.db;

import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.learn.RotationLearner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.example.wowmcp.util.Values.asBool;
import static com.example.wowmcp.util.Values.asDouble;
import static com.example.wowmcp.util.Values.asInt;
import static com.example.wowmcp.util.Values.asLong;
import static com.example.wowmcp.util.Values.str;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EventCollector {

    private static final Logger log = LoggerFactory.getLogger(EventCollector.class);

    private final GameBridge bridge;
    private final Database db;
    private final CastHistoryRepository casts;
    private final KillRepository kills;
    private final SpellIdResolver spellIds;
    private final HuntRepository hunts;
    private final LeaderLock leader;
    private final GameEventRepository gameEvents;
    private final com.example.wowmcp.roads.RoadService roads;
    private final PathRepository paths;
    private final ProfileRepository profiles;
    private final RotationLearner learner;
    private final BotLogRepository botLog;
    private final ObjectMapper json = new ObjectMapper();

    /** Накопители для минутного лога «события: cast=N, kill=M, вставлено cast=K». */
    private final java.util.concurrent.atomic.AtomicInteger statCasts = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statKills = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statCastsInserted = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statKeys = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statDrains = new java.util.concurrent.atomic.AtomicInteger();
    /** Когда последний раз страница ответила на опрос событий / пришли непустые события (для самопроверки). */
    private volatile long lastDrainAt;
    private volatile long lastEventAt;

    public long lastDrainAt() {
        return lastDrainAt;
    }

    public long lastEventAt() {
        return lastEventAt;
    }

    /** Причины пропуска опроса за минуту: не лидер / страница занята / бот не загружен / пустой ответ. */
    private final java.util.concurrent.atomic.AtomicInteger skipNotLeader = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger skipBusy = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger skipNotLoaded = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger skipEmpty = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger statInsertErrors = new java.util.concurrent.atomic.AtomicInteger();

    public EventCollector(GameBridge bridge, Database db, CastHistoryRepository casts, KillRepository kills, SpellIdResolver spellIds, HuntRepository hunts, LeaderLock leader, GameEventRepository gameEvents, com.example.wowmcp.roads.RoadService roads, PathRepository paths, ProfileRepository profiles, RotationLearner learner, BotLogRepository botLog) {
        this.bridge = bridge;
        this.db = db;
        this.casts = casts;
        this.kills = kills;
        this.spellIds = spellIds;
        this.hunts = hunts;
        this.leader = leader;
        this.gameEvents = gameEvents;
        this.roads = roads;
        this.paths = paths;
        this.profiles = profiles;
        this.botLog = botLog;
        this.learner = learner;
    }

    /** События, о которых стоит уведомить игрока (смерть, конец задачи, продажа). */

    /** События статистики (добыча, смерти, продажи, снимки опыта/денег): остальные поля уходят в data (jsonb). */
    private static final java.util.Set<String> STAT_TYPES = java.util.Set.of("loot", "lootmiss", "death", "sell", "stat");
    /** Поля, которые лежат в колонках, а не в data. */
    private static final java.util.Set<String> EVENT_COLUMNS =
            java.util.Set.of("type", "source", "jobUid", "zone", "x", "y", "at");
    private static final java.util.Set<String> LOG_COLUMNS =
            java.util.Set.of("type", "profile", "level", "cat", "msg", "jobUid", "zone", "x", "y", "at");
    private static final java.util.Set<String> JOB_COLUMNS =
            java.util.Set.of("type", "phase", "uid", "name", "status", "result", "at");

    /** Остальные поля события — JSON для колонки data (null, если их нет). */
    private String dataOf(Map<String, Object> ev, java.util.Set<String> columns) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ev.entrySet()) {
            if (!columns.contains(e.getKey())) data.put(e.getKey(), e.getValue());
        }
        return data.isEmpty() ? null : json.writeValueAsString(data);
    }

    @Scheduled(fixedDelay = 3000)
    public void collect() {
        if (!leader.isLeader()) {
            skipNotLeader.incrementAndGet();
            return;                       // события читает только лидер: иначе статистика задвоится
        }
        if (bridge.isBusy()) {
            skipBusy.incrementAndGet();
            return;
        }
        if (!bridge.isBotLoadedIfConnected()) {
            skipNotLoaded.incrementAndGet();
            bridge.requestWarmUp();           // лидер сам восстанавливает подключение, а не ждёт вызова инструмента
            return;
        }
        Optional<String> drained = bridge.evalIfReady("bot.drainEvents()", 2000);
        if (drained.isEmpty()) {
            skipEmpty.incrementAndGet();
            return;
        }
        String payload = drained.get();
        statDrains.incrementAndGet();
        lastDrainAt = System.currentTimeMillis();
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
        if (!events.isEmpty()) {
            lastEventAt = System.currentTimeMillis();
        }
        int killN = 0, pathN = 0, keyN = 0, castN = 0;
        String lastProfile = null;
        for (Map<String, Object> ev : events) {
            String type = String.valueOf(ev.get("type"));
            if ("key".equals(type)) {
                // key_press больше не наполняем: нажатия дублируют spell_cast и путают обучение
                keyN++;
                statKeys.incrementAndGet();
            } else if ("cast".equals(type)) {
                castN++;
                statCasts.incrementAndGet();
                if (db.isEnabled()) {
                    String profile = str(ev.get("profile"));
                    if (profile == null || profile.isBlank()) {
                        profile = "default";   // в БД profile NOT NULL — не теряем событие
                    }
                    lastProfile = profile;
                    Integer nameHash = asInt(ev.get("spellId")); // JS кладёт spellId = nameHash
                    Integer spellId = asInt(ev.get("nameHash"));  // запасной вариант, если пришёл отдельно
                    if (spellId == null) spellId = spellIds.resolve(nameHash);   // spellId по nameHash из bot.spells()
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
                    boolean inserted = casts.insertSpellCast(profile, nameHash == null ? 0 : nameHash, spellId, hp, mana,
                            fight, target, targetHpPct, readySpells, sinceFightMs, prevSpell, prevSpellMs,
                            at == 0 ? System.currentTimeMillis() : at);
                    if (inserted) {
                        statCastsInserted.incrementAndGet();
                    } else {
                        statInsertErrors.incrementAndGet();
                    }
                }
            } else if ("kill".equals(type)) {
                killN++;
                statKills.incrementAndGet();
                if (db.isEnabled()) {
                    Integer zone = asInt(ev.get("zone"));
                    Double x = asDouble(ev.get("x"));
                    Double y = asDouble(ev.get("y"));
                    Integer mobLevel = asInt(ev.get("mobLevel"));
                    Integer playerLevel = asInt(ev.get("playerLevel"));
                    String source = String.valueOf(ev.get("source"));
                    long at = asLong(ev.get("at"));
                    kills.insertMobKill(zone == null ? 0 : zone, x == null ? 0 : x, y == null ? 0 : y,
                            mobLevel, playerLevel, source, at == 0 ? System.currentTimeMillis() : at,
                            str(ev.get("jobUid")));
                }
            } else if ("job".equals(type)) {
                if (db.isEnabled()) {
                    String uid = str(ev.get("uid")), name = str(ev.get("name"));
                    long at = asLong(ev.get("at"));
                    if (at == 0) at = System.currentTimeMillis();
                    if ("start".equals(str(ev.get("phase")))) {
                        gameEvents.jobStart(uid, name, at);
                    } else {
                        gameEvents.jobEnd(uid, name, at, str(ev.get("status")), str(ev.get("result")), dataOf(ev, JOB_COLUMNS));
                    }
                }
            } else if (STAT_TYPES.contains(type)) {
                if (db.isEnabled()) {
                    long at = asLong(ev.get("at"));
                    gameEvents.insertEvent(type, str(ev.get("source")), str(ev.get("jobUid")), asInt(ev.get("zone")),
                            asDouble(ev.get("x")), asDouble(ev.get("y")), at == 0 ? System.currentTimeMillis() : at,
                            dataOf(ev, EVENT_COLUMNS));
                }
            } else if ("log".equals(type)) {
                if (db.isEnabled()) {
                    long at = asLong(ev.get("at"));
                    botLog.insert(at == 0 ? System.currentTimeMillis() : at, str(ev.get("profile")), str(ev.get("level")),
                            str(ev.get("cat")), str(ev.get("msg")), asInt(ev.get("zone")), asDouble(ev.get("x")),
                            asDouble(ev.get("y")), str(ev.get("jobUid")), dataOf(ev, LOG_COLUMNS));
                }
            } else if ("road".equals(type)) {
                if (db.isEnabled()) {
                    Integer zone = asInt(ev.get("zone"));
                    Double ax = asDouble(ev.get("ax")), ay = asDouble(ev.get("ay")), bx = asDouble(ev.get("bx")), by = asDouble(ev.get("by"));
                    String kind = str(ev.get("kind"));
                    if (zone != null && ax != null && ay != null && bx != null && by != null && kind != null) {
                        switch (kind) {
                            case "learn" -> roads.learnEdge(zone, ax, ay, bx, by);
                            case "ok" -> roads.legResult(zone, ax, ay, bx, by, true);
                            case "fail" -> roads.legResult(zone, ax, ay, bx, by, false);
                            default -> { }
                        }
                    }
                }
            } else if ("hunt".equals(type)) {
                if (db.isEnabled()) {
                    Integer zoneId = asInt(ev.get("zoneId"));
                    Double cx = asDouble(ev.get("cx")), cy = asDouble(ev.get("cy"));
                    if (zoneId != null && cx != null && cy != null) {
                        Integer kills = asInt(ev.get("kills")), emptyMin = asInt(ev.get("emptyMinutes"));
                        hunts.recordVisit(zoneId, cx, cy, Boolean.TRUE.equals(asBool(ev.get("found"))),
                                kills == null ? 0 : kills, emptyMin == null ? 0 : emptyMin, str(ev.get("note")));
                    }
                }
            } else if ("path".equals(type)) {
                pathN++;
                if (db.isEnabled()) {
                    String sessionId = String.valueOf(ev.get("session"));
                    Integer zone = asInt(ev.get("zone"));
                    Double x = asDouble(ev.get("x"));
                    Double y = asDouble(ev.get("y"));
                    long at = asLong(ev.get("at"));
                    String source = str(ev.get("source"));
                    paths.insertPathPoint(sessionId, zone == null ? 0 : zone, x == null ? 0 : x,
                            y == null ? 0 : y, at == 0 ? System.currentTimeMillis() : at,
                            source == null || source.isBlank() ? "manual" : source);
                }
            }
        }
        if (killN + pathN + keyN + castN > 0) {
            log.debug("Собрано событий: kills={}, paths={}, keys={}, casts={}", killN, pathN, keyN, castN);
        }

        // Автообучение: если профиль активен и накопилось >=40 новых применений
        if (db.isEnabled() && lastProfile != null && learner.isAutoLearnEnabled(lastProfile)) {
            try {
                String activeProfile = String.valueOf(bridge.eval("() => bot.profile"));
                if (lastProfile.equals(activeProfile)) {
                    List<Map<String, Object>> spellRows = casts.querySpellCasts(lastProfile);
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
                                profiles.upsertRotationProfile(lastProfile, rotJson, res.samples(), statsJson);
                                log.info("Автообучение профиля '{}': {}", lastProfile, res.rotation());
                            }
                        }
                    } else {
                        int total = casts.queryKeyPresses(lastProfile).size();
                        if (total - learner.lastLearnedCount(lastProfile) >= 40) {
                            RotationLearner.Result res = learner.learn(lastProfile);
                            if (res != null) {
                                String rotJson = json.writeValueAsString(res.rotation());
                                bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
                                String statsJson = res.stats() == null ? null : json.writeValueAsString(res.stats());
                                profiles.upsertRotationProfile(lastProfile, rotJson, res.samples(), statsJson);
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

    /** Раз в сутки: журнал бота хранится {@link BotLogRepository#KEEP_DAYS} дней. */
    @Scheduled(fixedDelay = 24 * 3_600_000L, initialDelay = 120_000)
    public void purgeBotLog() {
        if (!leader.isLeader()) return;
        int n = botLog.purgeOld();
        if (n > 0) log.info("лог бота: удалено старых записей {}", n);
    }

    /**
     * Раз в минуту: даже когда событий нет, видно, что сборщик жив и БД доступна.
     * Числа — за прошедшую минуту.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void logStats() {
        log.info("события: cast={}, kill={}, вставлено cast={} (key={}, опросов страницы={}, ошибок вставки={}, БД {}; пропуски: не лидер={}, занята={}, бот не загружен={}, пустой ответ={})",
                statCasts.getAndSet(0), statKills.getAndSet(0), statCastsInserted.getAndSet(0),
                statKeys.getAndSet(0), statDrains.getAndSet(0), statInsertErrors.getAndSet(0),
                db.isEnabled() ? "включена" : "выключена",
                skipNotLeader.getAndSet(0), skipBusy.getAndSet(0), skipNotLoaded.getAndSet(0), skipEmpty.getAndSet(0));
    }
}
