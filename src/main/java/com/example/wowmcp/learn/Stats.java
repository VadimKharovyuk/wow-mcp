package com.example.wowmcp.learn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Простая статистика для обучения ротации. */
final class Stats {

    private Stats() {
    }

    static double median(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(vals);
        s.sort(Comparator.naturalOrder());
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double medianInt(List<Integer> vals) {
        if (vals.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(vals);
        s.sort(Comparator.naturalOrder());
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double percentile(List<Double> vals, double p) {
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

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
