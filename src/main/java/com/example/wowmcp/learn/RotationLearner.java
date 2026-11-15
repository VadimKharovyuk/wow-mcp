package com.example.wowmcp.learn;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.CastHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.wowmcp.learn.Stats.median;
import static com.example.wowmcp.learn.Stats.medianInt;
import static com.example.wowmcp.learn.Stats.percentile;
import static com.example.wowmcp.learn.Stats.round2;
import static com.example.wowmcp.util.Values.asBool;
import static com.example.wowmcp.util.Values.asDouble;
import static com.example.wowmcp.util.Values.asInt;
import static com.example.wowmcp.util.Values.str;

/**
 * Обучение ротации по нажатиям игрока (профили персонажей).
 * Возвращает ротацию в формате fight() или null, если данных недостаточно.
 */
@org.springframework.stereotype.Component
public class RotationLearner {

    private static final Logger log = LoggerFactory.getLogger(RotationLearner.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Database db;
    private final CastHistoryRepository casts;
    private final LearnPolicy policy;
    private final Map<String, Integer> lastLearnedCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastStatsJson = new ConcurrentHashMap<>();

    public RotationLearner(Database db, CastHistoryRepository casts, LearnPolicy policy) {
        this.db = db;
        this.casts = casts;
        this.policy = policy;
    }

    /** Включено ли автообучение для профиля (см. {@link LearnPolicy}). */
    public boolean isAutoLearnEnabled(String profile) {
        return policy.isAutoLearnEnabled(profile);
    }

    /** Результат обучения. */
    public record Result(List<Map<String, Object>> rotation, Map<String, Object> stats, int samples) {}

    /** Результат попытки обучения по spell_cast: либо успех, либо причина отказа без fallback на key_press. */
    public record SpellLearn(Result result, boolean hasData, int inCombatCasts, int requiredInCombat) {
        public boolean isInsufficient() { return hasData && result == null; }
    }

    public boolean isEnabled() {
        return db.isEnabled();
    }

    public int lastLearnedCount(String profile) {
        return lastLearnedCount.getOrDefault(profile, 0);
    }

    public void setLastLearnedCount(String profile, int count) {
        lastLearnedCount.put(profile, count);
    }

    public String lastStatsJson(String profile) {
        return lastStatsJson.get(profile);
    }

    /**
     * Обучиться по профилю по key_press (legacy v1). Возвращает null, если данных недостаточно или БД отключена.
     * Используется только как fallback, когда spell_cast для профиля полностью пуст.
     */
    public Result learn(String profile) {
        if (!db.isEnabled() || profile == null || profile.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = casts.queryKeyPresses(profile);
        if (rows.isEmpty()) {
            return null;
        }

        // Только клавиши 1..9
        List<Map<String, Object>> presses = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String key = str(r.get("key"));
            if (key == null || !key.matches("[1-9]")) continue;
            presses.add(r);
        }
        if (presses.isEmpty()) {
            return null;
        }

        // Минимум данных: >= 3 боёв (стартов с sinceFightMs ~0) ИЛИ >= 60 нажатий в бою
        int inCombatPresses = 0;
        int fightStarts = 0;
        for (Map<String, Object> r : presses) {
            Boolean fight = asBool(r.get("fight"));
            Integer sfm = asInt(r.get("since_fight_ms"));
            if (Boolean.TRUE.equals(fight)) inCombatPresses++;
            if (sfm != null && sfm <= 500) fightStarts++;
        }
        // fightStarts может завышаться — считаем уникальные по грубой оценке: /3
        int approxFights = Math.max(1, fightStarts / 3);
        if (approxFights < 3 && inCombatPresses < 60) {
            log.info("Профиль '{}': недостаточно данных (боёв ~{}, нажатий в бою {}) — обучение пропущено",
                    profile, approxFights, inCombatPresses);
            return null;
        }

        // Группировка по клавишам
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : presses) {
            byKey.computeIfAbsent(str(r.get("key")), k -> new ArrayList<>()).add(r);
        }

        // Учитываем клавишу только если >= 5 нажатий
        List<String> considered = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
            if (e.getValue().size() >= 5) considered.add(e.getKey());
        }
        if (considered.isEmpty()) {
            return null;
        }

        // Медиана hp по всем нажатиям в бою (для аварийного правила)
        List<Double> allCombatHp = new ArrayList<>();
        for (Map<String, Object> r : presses) {
            if (Boolean.TRUE.equals(asBool(r.get("fight")))) {
                Double hp = asDouble(r.get("hp"));
                if (hp != null) allCombatHp.add(hp);
            }
        }
        double medAllCombat = median(allCombatHp);

        // Классификация клавиш
        Map<String, Boolean> emergency = new HashMap<>();
        Map<String, Boolean> opening = new HashMap<>();
        Map<String, Double> maxHpPct = new HashMap<>();
        Map<String, String> waitAfterKey = new HashMap<>();
        Map<String, Integer> waitAfterMs = new HashMap<>();

        for (String key : considered) {
            List<Map<String, Object>> kp = byKey.get(key);
            // Открывающее: >=60% нажатий вне боя
            long outOfCombat = kp.stream().filter(r -> !Boolean.TRUE.equals(asBool(r.get("fight")))).count();
            if (kp.size() > 0 && (double) outOfCombat / kp.size() >= 0.6) {
                opening.put(key, true);
            } else {
                opening.put(key, false);
            }

            // Аварийное: p90 hp в бою < 0.6 и medAllCombat > 0.7
            List<Double> inCombatHp = new ArrayList<>();
            for (Map<String, Object> r : kp) {
                if (Boolean.TRUE.equals(asBool(r.get("fight")))) {
                    Double hp = asDouble(r.get("hp"));
                    if (hp != null) inCombatHp.add(hp);
                }
            }
            boolean isEmergency = false;
            if (inCombatHp.size() >= 3 && medAllCombat > 0.7) {
                double p90 = percentile(inCombatHp, 0.9);
                if (p90 < 0.6) {
                    isEmergency = true;
                    maxHpPct.put(key, round2(p90 + 0.05));
                }
            }
            emergency.put(key, isEmergency);

            // Связка «после X»
            Map<String, List<Integer>> byPrev = new HashMap<>();
            for (Map<String, Object> r : kp) {
                String pk = str(r.get("prev_key"));
                Integer pm = asInt(r.get("prev_key_ms"));
                if (pk != null && pk.matches("[1-9]") && pm != null && pm <= 3000) {
                    byPrev.computeIfAbsent(pk, k -> new ArrayList<>()).add(pm);
                }
            }
            String bestPrev = null;
            int bestCount = 0;
            for (Map.Entry<String, List<Integer>> e : byPrev.entrySet()) {
                if (e.getValue().size() >= Math.ceil(kp.size() * 0.7) && e.getValue().size() > bestCount) {
                    bestCount = e.getValue().size();
                    bestPrev = e.getKey();
                }
            }
            if (bestPrev != null) {
                waitAfterKey.put(key, bestPrev);
                List<Integer> msList = byPrev.get(bestPrev);
                int medMs = (int) Math.round(medianInt(msList) / 100.0) * 100;
                waitAfterMs.put(key, medMs);
            }
        }

        // Приоритет по Копленду
        // wins[K][J]
        Map<String, Map<String, Integer>> wins = new HashMap<>();
        for (String k : considered) wins.put(k, new HashMap<>());
        // Для каждой клавиши K, для каждого её нажатия, смотрим ready-маску
        for (String k : considered) {
            for (Map<String, Object> r : byKey.get(k)) {
                int ready = asInt(r.get("ready")) == null ? 0 : asInt(r.get("ready"));
                for (String j : considered) {
                    if (j.equals(k)) continue;
                    int slotBit = 1 << (Integer.parseInt(j) - 1);
                    if ((ready & slotBit) != 0) {
                        wins.get(k).merge(j, 1, Integer::sum);
                    }
                }
            }
        }

        // Группы
        List<String> emergencyKeys = new ArrayList<>();
        List<String> openingKeys = new ArrayList<>();
        List<String> restKeys = new ArrayList<>();
        for (String key : considered) {
            if (Boolean.TRUE.equals(emergency.get(key))) emergencyKeys.add(key);
            else if (Boolean.TRUE.equals(opening.get(key))) openingKeys.add(key);
            else restKeys.add(key);
        }

        Comparator<String> copeland = (a, b) -> {
            int wa = wins.get(a).getOrDefault(b, 0);
            int wb = wins.get(b).getOrDefault(a, 0);
            if (wa != wb) return Integer.compare(wb, wa); // higher wins first
            // tie-break by count (frequency) desc
            return Integer.compare(byKey.get(b).size(), byKey.get(a).size());
        };

        emergencyKeys.sort(copeland);
        openingKeys.sort(copeland);
        restKeys.sort(copeland);

        List<String> finalOrder = new ArrayList<>();
        finalOrder.addAll(emergencyKeys);
        finalOrder.addAll(openingKeys);
        finalOrder.addAll(restKeys);

        // Построение ротации
        List<Map<String, Object>> rotation = new ArrayList<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String key : finalOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            List<String> rules = new ArrayList<>();
            if (Boolean.TRUE.equals(emergency.get(key))) {
                entry.put("maxHpPct", maxHpPct.get(key));
                List<Map<String, Object>> kp = byKey.get(key);
                long inC = kp.stream().filter(r -> Boolean.TRUE.equals(asBool(r.get("fight")))).count();
                rules.add(String.format("HP < %.2f в %d из %d нажатий в бою",
                        maxHpPct.get(key), inC, kp.size()));
            }
            if (Boolean.TRUE.equals(opening.get(key))) {
                entry.put("outOfCombatOnly", true);
                List<Map<String, Object>> kp = byKey.get(key);
                long outC = kp.stream().filter(r -> !Boolean.TRUE.equals(asBool(r.get("fight")))).count();
                rules.add(String.format("открывающий удар в %d%% случаев вне боя",
                        (int) Math.round(100.0 * outC / kp.size())));
            }
            if (waitAfterKey.containsKey(key)) {
                entry.put("waitAfter", Map.of("key", waitAfterKey.get(key), "ms", waitAfterMs.get(key)));
                rules.add(String.format("после %s (медиана %d мс)", waitAfterKey.get(key), waitAfterMs.get(key)));
            }
            if (rules.isEmpty()) {
                rules.add("базовый удар");
            }
            rotation.add(entry);

            Map<String, Object> keyStats = new LinkedHashMap<>();
            keyStats.put("count", byKey.get(key).size());
            keyStats.put("rule", String.join("; ", rules));
            stats.put(key, keyStats);
        }

        int samples = presses.size();
        String statsJson = null;
        try {
            statsJson = JSON.writeValueAsString(stats);
        } catch (Exception ignored) {
        }
        lastStatsJson.put(profile, statsJson);
        lastLearnedCount.put(profile, casts.queryKeyPresses(profile).size());

        log.info("Профиль '{}': выучена ротация {} (samples={})", profile, rotation, samples);
        return new Result(rotation, stats, samples);
    }

    /**
     * Обучение ротации v2 по применённым заклинаниям (spell_cast, по nameHash).
     * Если spell_cast для профиля пуст — hasData=false (можно fallback на key_press).
     * Если есть данные, но их недостаточно — hasData=true, result=null (fallback запрещён).
     * currentRotation — текущая ротация в игре: правила, заданные вручную (maxHpPct, maxPetHpPct,
     * repeatMs или key, без пометки learned), переносятся как есть и стоят в начале.
     * Заклинания из {@link LearnPolicy} (автострельба, атака, кормление/лечение питомца) не учатся.
     */
    public SpellLearn learnSpells(String profile, List<Map<String, Object>> currentRotation) {
        final int REQUIRED_IN_COMBAT = 40;
        if (!db.isEnabled() || profile == null || profile.isBlank()) {
            return new SpellLearn(null, false, 0, REQUIRED_IN_COMBAT);
        }
        List<Map<String, Object>> rows = casts.querySpellCasts(profile);
        if (rows.isEmpty()) {
            return new SpellLearn(null, false, 0, REQUIRED_IN_COMBAT);
        }
        // Исключённые заклинания (автострельба, атака, кормление, призыв и лечение питомца) не учим:
        // по nameHash и по spell_id (если по spell_id нашли хеш — исключаем его везде, в т.ч. как «после чего»)
        Set<Integer> excluded = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Integer hash = asInt(r.get("name_hash"));
            if (hash != null && (policy.isExcluded(hash) || policy.isExcludedSpellId(asInt(r.get("spell_id"))))) {
                excluded.add(hash);
            }
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Integer hash = asInt(r.get("name_hash"));
            if (hash == null || excluded.contains(hash)) continue;
            history.add(r);
        }
        if (history.isEmpty()) {
            return new SpellLearn(null, true, 0, REQUIRED_IN_COMBAT);
        }

        int inCombatCasts = 0;
        for (Map<String, Object> r : history) {
            if (Boolean.TRUE.equals(asBool(r.get("fight")))) inCombatCasts++;
        }
        if (inCombatCasts < REQUIRED_IN_COMBAT) {
            log.info("Профиль '{}': недостаточно применений заклинаний в бою ({} < {}) — обучение пропущено, ротация сохранена",
                    profile, inCombatCasts, REQUIRED_IN_COMBAT);
            return new SpellLearn(null, true, inCombatCasts, REQUIRED_IN_COMBAT);
        }

        Map<Integer, List<Map<String, Object>>> byHash = new LinkedHashMap<>();
        for (Map<String, Object> r : history) {
            byHash.computeIfAbsent(asInt(r.get("name_hash")), k -> new ArrayList<>()).add(r);
        }

        List<Integer> considered = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byHash.entrySet()) {
            if (e.getValue().size() >= 5) considered.add(e.getKey());
        }
        if (considered.isEmpty()) {
            log.info("Профиль '{}': нет заклинаний с >=5 применениями — обучение пропущено", profile);
            return new SpellLearn(null, true, inCombatCasts, REQUIRED_IN_COMBAT);
        }

        List<Double> allCombatHp = new ArrayList<>();
        for (Map<String, Object> r : history) {
            if (Boolean.TRUE.equals(asBool(r.get("fight")))) {
                Double hp = asDouble(r.get("hp"));
                if (hp != null) allCombatHp.add(hp);
            }
        }
        double medAllCombat = median(allCombatHp);

        Map<Integer, Boolean> emergency = new HashMap<>();
        Map<Integer, Boolean> opening = new HashMap<>();
        Map<Integer, Double> maxHpPct = new HashMap<>();
        Map<Integer, Integer> waitAfterSpell = new HashMap<>();
        Map<Integer, Integer> waitAfterMs = new HashMap<>();

        for (Integer hash : considered) {
            List<Map<String, Object>> cp = byHash.get(hash);
            long outOfCombat = cp.stream().filter(r -> !Boolean.TRUE.equals(asBool(r.get("fight")))).count();
            opening.put(hash, cp.size() > 0 && (double) outOfCombat / cp.size() >= 0.6);

            List<Double> inCombatHp = new ArrayList<>();
            for (Map<String, Object> r : cp) {
                if (Boolean.TRUE.equals(asBool(r.get("fight")))) {
                    Double hp = asDouble(r.get("hp"));
                    if (hp != null) inCombatHp.add(hp);
                }
            }
            boolean isEmergency = false;
            if (inCombatHp.size() >= 3 && medAllCombat > 0.7) {
                double p90 = percentile(inCombatHp, 0.9);
                if (p90 < 0.6) {
                    isEmergency = true;
                    maxHpPct.put(hash, round2(p90 + 0.05));
                }
            }
            emergency.put(hash, isEmergency);

            Map<Integer, List<Integer>> byPrev = new HashMap<>();
            for (Map<String, Object> r : cp) {
                Integer pk = asInt(r.get("prev_spell"));
                Integer pm = asInt(r.get("prev_spell_ms"));
                if (pk != null && pm != null && pm <= 3000 && !excluded.contains(pk) && !policy.isExcluded(pk)) {
                    byPrev.computeIfAbsent(pk, k -> new ArrayList<>()).add(pm);
                }
            }
            Integer bestPrev = null;
            int bestCount = 0;
            for (Map.Entry<Integer, List<Integer>> e : byPrev.entrySet()) {
                if (e.getValue().size() >= Math.ceil(cp.size() * 0.7) && e.getValue().size() > bestCount) {
                    bestCount = e.getValue().size();
                    bestPrev = e.getKey();
                }
            }
            if (bestPrev != null) {
                waitAfterSpell.put(hash, bestPrev);
                int medMs = (int) Math.round(medianInt(byPrev.get(bestPrev)) / 100.0) * 100;
                waitAfterMs.put(hash, medMs);
            }
        }

        Map<Integer, Map<Integer, Integer>> wins = new HashMap<>();
        for (Integer k : considered) wins.put(k, new HashMap<>());
        for (Integer k : considered) {
            for (Map<String, Object> r : byHash.get(k)) {
                Set<Integer> readySet = readySet(r.get("ready_spells"));
                for (Integer j : considered) {
                    if (j.equals(k)) continue;
                    if (readySet.contains(j)) wins.get(k).merge(j, 1, Integer::sum);
                }
            }
        }

        List<Integer> emergencySpells = new ArrayList<>();
        List<Integer> openingSpells = new ArrayList<>();
        List<Integer> restSpells = new ArrayList<>();
        for (Integer hash : considered) {
            if (Boolean.TRUE.equals(emergency.get(hash))) emergencySpells.add(hash);
            else if (Boolean.TRUE.equals(opening.get(hash))) openingSpells.add(hash);
            else restSpells.add(hash);
        }
        Comparator<Integer> copeland = (a, b) -> {
            int wa = wins.get(a).getOrDefault(b, 0);
            int wb = wins.get(b).getOrDefault(a, 0);
            if (wa != wb) return Integer.compare(wb, wa);
            return Integer.compare(byHash.get(b).size(), byHash.get(a).size());
        };
        emergencySpells.sort(copeland);
        openingSpells.sort(copeland);
        restSpells.sort(copeland);

        // Атакующая/выученная часть (без аварийных, добавленных алгоритмом — их отдельно обработаем через перенос)
        // Сохраняем порядок: выученные аварийные, затем открывающие, затем остальные.
        // Но аварийные из currentRotation с <5 данными надо перенести "как есть" и не дублировать.
        Set<Integer> learnedHashes = new HashSet<>(considered);

        List<Integer> finalOrder = new ArrayList<>();
        finalOrder.addAll(emergencySpells);
        finalOrder.addAll(openingSpells);
        finalOrder.addAll(restSpells);

        List<Map<String, Object>> rotation = new ArrayList<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        Set<Integer> added = new HashSet<>();

        // Сначала переносим правила, заданные вручную (условия maxHpPct / maxPetHpPct / repeatMs или слот key):
        // обучение их не затирает. Заклинание такого правила дальше не учим заново.
        if (currentRotation != null) {
            for (Map<String, Object> cur : currentRotation) {
                if (!LearnPolicy.isManualRule(cur)) continue;
                rotation.add(new LinkedHashMap<>(cur));
                Integer spell = asInt(cur.get("spell"));
                Map<String, Object> hs = new LinkedHashMap<>();
                hs.put("rule", "сохранено правило из текущей ротации");
                if (spell != null) {
                    added.add(spell);
                    hs.put("count", byHash.containsKey(spell) ? byHash.get(spell).size() : 0);
                    stats.put(String.valueOf(spell), hs);
                } else {
                    hs.put("count", 0);
                    stats.put("key:" + cur.get("key"), hs);
                }
            }
        }

        for (Integer hash : finalOrder) {
            if (added.contains(hash)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("spell", hash);
            entry.put("learned", true);   // выучено автоматически: при следующем обучении пересчитывается
            List<String> rules = new ArrayList<>();
            if (Boolean.TRUE.equals(emergency.get(hash))) {
                entry.put("maxHpPct", maxHpPct.get(hash));
                long inC = byHash.get(hash).stream().filter(r -> Boolean.TRUE.equals(asBool(r.get("fight")))).count();
                rules.add(String.format("HP < %.2f в %d из %d применений в бою", maxHpPct.get(hash), inC, byHash.get(hash).size()));
            }
            if (Boolean.TRUE.equals(opening.get(hash))) {
                entry.put("outOfCombatOnly", true);
                long outC = byHash.get(hash).stream().filter(r -> !Boolean.TRUE.equals(asBool(r.get("fight")))).count();
                rules.add(String.format("открывающий удар в %d%% случаев вне боя", (int) Math.round(100.0 * outC / byHash.get(hash).size())));
            }
            if (waitAfterSpell.containsKey(hash)) {
                entry.put("waitAfter", Map.of("spell", waitAfterSpell.get(hash), "ms", waitAfterMs.get(hash)));
                rules.add(String.format("после spell %d (медиана %d мс)", waitAfterSpell.get(hash), waitAfterMs.get(hash)));
            }
            if (rules.isEmpty()) rules.add("базовый удар");
            rotation.add(entry);
            Map<String, Object> hs = new LinkedHashMap<>();
            hs.put("count", byHash.get(hash).size());
            hs.put("rule", String.join("; ", rules));
            stats.put(String.valueOf(hash), hs);
        }

        int samples = history.size();
        String statsJson = null;
        try { statsJson = JSON.writeValueAsString(stats); } catch (Exception ignored) {}
        lastStatsJson.put(profile, statsJson);
        // считаем все строки spell_cast (в т.ч. исключённые): с ними сравнивает EventCollector
        lastLearnedCount.put(profile, rows.size());
        log.info("Профиль '{}': выучена ротация v2 {} (samples={})", profile, rotation, samples);
        return new SpellLearn(new Result(rotation, stats, samples), true, inCombatCasts, REQUIRED_IN_COMBAT);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> readySet(Object v) {
        Set<Integer> out = new HashSet<>();
        if (v == null) return out;
        if (v instanceof java.sql.Array arr) {
            try { Object a = arr.getArray(); if (a instanceof Object[] oa) for (Object o : oa) { Integer i = asInt(o); if (i != null) out.add(i); } } catch (Exception ignored) {}
        } else if (v instanceof Object[] oa) {
            for (Object o : oa) { Integer i = asInt(o); if (i != null) out.add(i); }
        } else if (v instanceof List<?> list) {
            for (Object o : list) { Integer i = asInt(o); if (i != null) out.add(i); }
        }
        return out;
    }

    // ---- helpers ----
}
