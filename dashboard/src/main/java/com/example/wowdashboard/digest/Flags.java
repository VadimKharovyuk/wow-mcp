package com.example.wowdashboard.digest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.wowdashboard.digest.Nums.dbl;
import static com.example.wowdashboard.digest.Nums.num;
import static com.example.wowdashboard.digest.Nums.sum;

/**
 * Выводы по цифрам, посчитанные программой (модель их пересказывает, а не вычисляет): низкая эффективность за период,
 * улучшение или ухудшение от первой части к последней, провал или всплеск в середине, смерти, изменение длительности боёв.
 * Пустой список — заметных отклонений нет.
 */
final class Flags {

    private Flags() {
    }

    static List<String> compute(List<Map<String, Object>> parts) {
        List<String> out = new ArrayList<>();
        rateFlags(out, parts, "поиск атакующего после убийства цели", "attacker_found", "attacker_missed");
        rateFlags(out, parts, "доворот к цели вне экрана", "turn_found", "turn_missed");
        rateFlags(out, parts, "посадка на коня при перебежке", "mount_ok", "mount_fail");
        rateFlags(out, parts, "подход к цели", "approach_ok", "approach_fail");
        long deaths = sum(parts, "deaths");
        if (deaths > 0) out.add("Смерти: " + deaths + " за период.");
        if (parts.size() == 3) {
            dipFlag(out, parts, "поиск атакующего после убийства цели", "attacker_found", "attacker_missed");
            dipFlag(out, parts, "подход к цели", "approach_ok", "approach_fail");
            dipFlag(out, parts, "посадка на коня при перебежке", "mount_ok", "mount_fail");
            double a = dbl(parts.get(0).get("avg_fight_sec")), m = dbl(parts.get(1).get("avg_fight_sec")), c = dbl(parts.get(2).get("avg_fight_sec"));
            if (num(parts.get(0).get("fights")) >= 5 && num(parts.get(1).get("fights")) >= 5 && num(parts.get(2).get("fights")) >= 5
                    && a > 0 && c > 0 && m >= Math.max(a, c) * 1.5) {
                out.add("Всплеск в середине периода: средняя длительность боя " + m + " с против " + a + " и " + c + " с в первой и последней частях.");
            }
        }
        if (parts.size() >= 2) {
            Map<String, Object> first = parts.get(0), last = parts.get(parts.size() - 1);
            double f0 = dbl(first.get("avg_fight_sec")), f1 = dbl(last.get("avg_fight_sec"));
            if (num(first.get("fights")) >= 5 && num(last.get("fights")) >= 5 && f0 > 0 && f1 > 0) {
                if (f1 >= f0 * 1.5) out.add("Ухудшение: средняя длительность боя выросла с " + f0 + " до " + f1 + " с (первая → последняя часть).");
                else if (f1 <= f0 * 0.67) out.add("Улучшение: средняя длительность боя снизилась с " + f0 + " до " + f1 + " с (первая → последняя часть).");
            }
        }
        return out;
    }

    /** Средняя часть заметно хуже обеих соседних (минимум 5 случаев в каждой): временный провал, который тренд «первая → последняя» не видит. */
    private static void dipFlag(List<String> out, List<Map<String, Object>> parts, String label, String okKey, String badKey) {
        long[] n = new long[3];
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            n[i] = num(parts.get(i).get(okKey)) + num(parts.get(i).get(badKey));
            if (n[i] < 5) return;
            r[i] = num(parts.get(i).get(okKey)) * 100.0 / n[i];
        }
        if (r[1] <= Math.min(r[0], r[2]) - 20) {
            out.add("Провал в середине периода: " + label + " " + Math.round(r[1]) + "% против " + Math.round(r[0]) + "% и " + Math.round(r[2])
                    + "% в первой и последней частях.");
        }
    }

    private static void rateFlags(List<String> out, List<Map<String, Object>> parts, String label, String okKey, String badKey) {
        long ok = sum(parts, okKey), bad = sum(parts, badKey), n = ok + bad;
        if (n >= 10 && ok * 2 < n) {
            out.add("Низкая эффективность: " + label + " — удалось " + Math.round(ok * 100.0 / n) + "% (" + ok + " из " + n + ") за весь период.");
        }
        if (parts.size() < 2) return;
        Map<String, Object> first = parts.get(0), last = parts.get(parts.size() - 1);
        long n0 = num(first.get(okKey)) + num(first.get(badKey)), n1 = num(last.get(okKey)) + num(last.get(badKey));
        if (n0 < 5 || n1 < 5) return;
        double r0 = num(first.get(okKey)) * 100.0 / n0, r1 = num(last.get(okKey)) * 100.0 / n1;
        if (r1 - r0 >= 30) {
            out.add("Улучшение: " + label + " выросло с " + Math.round(r0) + "% до " + Math.round(r1) + "% (первая → последняя часть) — похоже, исправлено.");
        } else if (r0 - r1 >= 30) {
            out.add("Ухудшение: " + label + " упало с " + Math.round(r0) + "% до " + Math.round(r1) + "% (первая → последняя часть).");
        }
    }
}
