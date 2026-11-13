package com.example.wowmcp.learn;

import com.example.wowmcp.db.DbService;
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

/**
 * Обучение ротации по нажатиям игрока (профили персонажей).
 * Возвращает ротацию в формате fight() или null, если данных недостаточно.
 */
@org.springframework.stereotype.Component
public class RotationLearner {

    private static final Logger log = LoggerFactory.getLogger(RotationLearner.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final DbService db;
    private final Map<String, Integer> lastLearnedCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastStatsJson = new ConcurrentHashMap<>();

    public RotationLearner(DbService db) {
        this.db = db;
    }

    /** Результат обучения. */
    public record Result(List<Map<String, Object>> rotation, Map<String, Object> stats, int samples) {}

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
     * Обучиться по профилю. Возвращает null, если данных недостаточно или БД отключена.
     */
    public Result learn(String profile) {
        if (!db.isEnabled() || profile == null || profile.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = db.queryKeyPresses(profile);
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
            // Открывающее: >=80% нажатий вне боя
            long outOfCombat = kp.stream().filter(r -> !Boolean.TRUE.equals(asBool(r.get("fight")))).count();
            if (kp.size() > 0 && (double) outOfCombat / kp.size() >= 0.8) {
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
        lastLearnedCount.put(profile, db.queryKeyPresses(profile).size());

        log.info("Профиль '{}': выучена ротация {} (samples={})", profile, rotation, samples);
        return new Result(rotation, stats, samples);
    }

    /**
     * Обучение ротации v2 по применённым заклинаниям (spell_cast, по nameHash).
     * Тот же алгоритм, что и learn(), но идентификатор — nameHash заклинания.
     */
    public Result learnSpells(String profile) {
        if (!db.isEnabled() || profile == null || profile.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = db.querySpellCasts(profile);
        if (rows.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> casts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Integer hash = asInt(r.get("name_hash"));
            if (hash == null) continue;
            casts.add(r);
        }
        if (casts.isEmpty()) return null;

        int inCombatCasts = 0;
        int fightStarts = 0;
        for (Map<String, Object> r : casts) {
            Boolean fight = asBool(r.get("fight"));
            Integer sfm = asInt(r.get("since_fight_ms"));
            if (Boolean.TRUE.equals(fight)) inCombatCasts++;
            if (sfm != null && sfm <= 500) fightStarts++;
        }
        int approxFights = Math.max(1, fightStarts / 3);
        if (approxFights < 3 && inCombatCasts < 60) {
            log.info("Профиль '{}': недостаточно применений заклинаний (боёв ~{}, в бою {})", profile, approxFights, inCombatCasts);
            return null;
        }

        Map<Integer, List<Map<String, Object>>> byHash = new LinkedHashMap<>();
        for (Map<String, Object> r : casts) {
            byHash.computeIfAbsent(asInt(r.get("name_hash")), k -> new ArrayList<>()).add(r);
        }

        List<Integer> considered = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byHash.entrySet()) {
            if (e.getValue().size() >= 5) considered.add(e.getKey());
        }
        if (considered.isEmpty()) return null;

        List<Double> allCombatHp = new ArrayList<>();
        for (Map<String, Object> r : casts) {
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
            opening.put(hash, cp.size() > 0 && (double) outOfCombat / cp.size() >= 0.8);

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
                if (pk != null && pm != null && pm <= 3000) {
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

        List<Integer> finalOrder = new ArrayList<>();
        finalOrder.addAll(emergencySpells);
        finalOrder.addAll(openingSpells);
        finalOrder.addAll(restSpells);

        List<Map<String, Object>> rotation = new ArrayList<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Integer hash : finalOrder) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("spell", hash);
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

        int samples = casts.size();
        String statsJson = null;
        try { statsJson = JSON.writeValueAsString(stats); } catch (Exception ignored) {}
        lastStatsJson.put(profile, statsJson);
        lastLearnedCount.put(profile, db.querySpellCasts(profile).size());
        log.info("Профиль '{}': выучена ротация v2 {} (samples={})", profile, rotation, samples);
        return new Result(rotation, stats, samples);
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

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
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

    private static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "t".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "f".equals(s)) return false;
        return null;
    }

    private static double median(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(vals);
        s.sort(Comparator.naturalOrder());
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double medianInt(List<Integer> vals) {
        if (vals.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(vals);
        s.sort(Comparator.naturalOrder());
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double percentile(List<Double> vals, double p) {
        if (vals.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(vals);
        s.sort(Comparator.naturalOrder());
        int n = s.size();
        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo < 0) lo = 0;
        if (hi >= n) hi = n - 1;
        if (lo == hi) return s.get(lo);
        double frac = idx - lo;
        return s.get(lo) * (1 - frac) + s.get(hi) * frac;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}