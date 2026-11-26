package com.example.wowdashboard.hunting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Лучшие места охоты по данным, без БД и без модели (чистые расчёты).
 *
 * <p>Опыт в час считается по снимкам опыта (они идут примерно раз в 30 секунд), поэтому это измерение, а не оценка по формуле.
 * Единица измерения — окно в 10 минут: опыт и время берутся из промежутков между соседними снимками на одном уровне (промежутки
 * больше 150 секунд — перерыв в работе, отбрасываются). В расчёт берутся только окна, где работал бот: есть его убийства, нет убийств
 * игрока (опыт общий) и покрыто не меньше 5 минут. Опыт и время окна разносятся по клеткам карты пропорционально убийствам бота в них
 * (с весом по разнице уровней моба и игрока: сильные мобы дают больше опыта). Окна на другом уровне не сравнимы и пропускаются.
 */
final class HuntingModel {

    static final int BUCKET_SEC = 600;
    static final int MAX_GAP_SEC = 150;
    static final int MIN_BUCKET_SEC = 300;
    static final double MIN_HOURS = 0.15;
    static final int MIN_KILLS = 5;
    /** Сглаживание частоты смертей и упоров: к времени в клетке добавляется этот «запас» часов. */
    static final double PRIOR_HOURS = 1.0;

    private HuntingModel() {
    }

    private static final class Bucket {
        long xp;
        long seconds;
    }

    private static final class Acc {
        double xp;
        double hours;
        long kills;
    }

    /** Клетка карты 2×2 (тот же шаг, что у карты убийств на странице): индекс центра. */
    static int cell(double v) {
        return (int) (Math.floor(v / 2) * 2 + 1);
    }

    private static String key(int zone, int cx, int cy) {
        return zone + ":" + cx + ":" + cy;
    }

    /** Вес убийства: опыт растёт с уровнем моба относительно игрока (для сравнимого уровня). Неизвестные уровни — вес 1. */
    static double weight(Integer mobLevel, Integer playerLevel) {
        if (mobLevel == null || playerLevel == null) return 1;
        return Math.max(0.2, Math.min(1.3, 1 + 0.05 * (mobLevel - playerLevel)));
    }

    /**
     * @param level уровень игрока для сравнения; null — уровень последнего снимка
     */
    static HuntingResult compute(List<Snapshot> snaps, List<Kill> kills, List<MapPoint> deaths, List<MapPoint> stucks, Integer level) {
        if (snaps.isEmpty()) return new HuntingResult(level, 0, 0, 0, List.of());
        int lvl = level != null ? level : snaps.get(snaps.size() - 1).level();

        Map<Long, Bucket> buckets = new HashMap<>();
        for (int i = 1; i < snaps.size(); i++) {
            Snapshot a = snaps.get(i - 1), b = snaps.get(i);
            if (a.level() != lvl || b.level() != lvl) continue;
            long gap = b.ts() - a.ts();
            long d = b.xp() - a.xp();
            if (gap <= 0 || gap > MAX_GAP_SEC || d < 0) continue;
            Bucket bk = buckets.computeIfAbsent(b.ts() / BUCKET_SEC, k -> new Bucket());
            bk.xp += d;
            bk.seconds += gap;
        }

        Map<Long, List<Kill>> botKills = new HashMap<>();
        Map<Long, Boolean> playerKills = new HashMap<>();
        for (Kill k : kills) {
            long b = k.ts() / BUCKET_SEC;
            if (k.bot()) botKills.computeIfAbsent(b, x -> new ArrayList<>()).add(k);
            else playerKills.put(b, true);
        }

        Map<String, Acc> cells = new HashMap<>();
        Map<String, int[]> coords = new HashMap<>();
        double totalXp = 0, totalHours = 0;
        int windows = 0;
        for (Map.Entry<Long, Bucket> e : buckets.entrySet()) {
            Bucket bk = e.getValue();
            List<Kill> ks = botKills.get(e.getKey());
            if (bk.seconds < MIN_BUCKET_SEC || ks == null || playerKills.containsKey(e.getKey())) continue;
            double totalW = 0;
            for (Kill k : ks) totalW += weight(k.mobLevel(), k.playerLevel());
            if (totalW <= 0) continue;
            windows++;
            totalXp += bk.xp;
            totalHours += bk.seconds / 3600.0;
            Map<String, Double> w = new HashMap<>();
            for (Kill k : ks) {
                int cx = cell(k.x()), cy = cell(k.y());
                String key = key(k.zone(), cx, cy);
                coords.putIfAbsent(key, new int[]{k.zone(), cx, cy});
                w.merge(key, weight(k.mobLevel(), k.playerLevel()), Double::sum);
                cells.computeIfAbsent(key, x -> new Acc()).kills++;
            }
            for (Map.Entry<String, Double> we : w.entrySet()) {
                double share = we.getValue() / totalW;
                Acc acc = cells.get(we.getKey());
                acc.xp += bk.xp * share;
                acc.hours += bk.seconds / 3600.0 * share;
            }
        }

        Map<String, Long> deathCount = count(deaths), stuckCount = count(stucks);
        double avg = totalHours > 0 ? totalXp / totalHours : 0;
        List<SpotStat> out = new ArrayList<>();
        for (Map.Entry<String, Acc> e : cells.entrySet()) {
            Acc a = e.getValue();
            if (a.hours < MIN_HOURS || a.kills < MIN_KILLS) continue;
            int[] c = coords.get(e.getKey());
            long deathsHere = deathCount.getOrDefault(e.getKey(), 0L), stuckHere = stuckCount.getOrDefault(e.getKey(), 0L);
            double xpH = a.xp / a.hours;
            // штрафы по частоте в час, сглаженной «лишним» часом: одна смерть на 20 минутах не должна обнулять клетку
            double deathFactor = Math.max(0.1, 1 - 0.3 * (deathsHere / (a.hours + PRIOR_HOURS)));
            double stuckFactor = Math.max(0.5, 1 - 0.02 * (stuckHere / (a.hours + PRIOR_HOURS)));
            double score = xpH * deathFactor * stuckFactor;
            long vs = avg > 0 ? Math.round((score / avg - 1) * 100) : 0;
            String conf = a.hours >= 1 ? "высокая" : a.hours >= 0.4 ? "средняя" : "низкая";
            out.add(new SpotStat(c[0], c[1], c[2], a.kills, Math.round(a.hours * 100) / 100.0, Math.round(xpH),
                    Math.round(a.kills / a.hours * 10) / 10.0, deathsHere, stuckHere, Math.round(score), vs, conf));
        }
        out.sort(Comparator.comparingLong(SpotStat::score).reversed());
        return new HuntingResult(lvl, windows, Math.round(totalHours * 100) / 100.0, Math.round(avg), out);
    }

    private static Map<String, Long> count(List<MapPoint> points) {
        Map<String, Long> m = new HashMap<>();
        for (MapPoint p : points) m.merge(key(p.zone(), cell(p.x()), cell(p.y())), 1L, Long::sum);
        return m;
    }
}
