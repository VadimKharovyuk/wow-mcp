package com.example.wowmcp.spots;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Лучшие места охоты за последние 24 часа. Результат расчёта по всем зонам кэшируется на 5 минут: инструменты и «иди качайся»
 * зовут сервис часто, а считать заново из БД каждый раз незачем.
 */
@Service
public class SpotService {

    static final int HOURS = 24;
    static final long TTL_MS = 5 * 60_000L;

    private final SpotRepository repo;
    private final LongSupplier clock;
    private volatile SpotResult cached;
    private volatile long cachedAt;

    @Autowired
    public SpotService(SpotRepository repo) {
        this(repo, System::currentTimeMillis);
    }

    SpotService(SpotRepository repo, LongSupplier clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** Расчёт по всем зонам (с кэшем). Нет БД или данных — пустой результат. */
    public SpotResult all() {
        long now = clock.getAsLong();
        SpotResult c = cached;
        if (c != null && now - cachedAt < TTL_MS) return c;
        SpotResult r;
        try {
            r = SpotModel.compute(repo.snapshots(HOURS), repo.kills(HOURS), repo.deaths(HOURS), repo.stucks(HOURS), null);
        } catch (Exception e) {
            return c != null ? c : new SpotResult(null, 0, 0, 0, List.of());
        }
        cached = r;
        cachedAt = now;
        return r;
    }

    /** Лучшие клетки зоны бота (от лучшей к худшей), не больше limit. */
    public List<SpotStat> best(int zone, int limit) {
        return all().spots().stream().filter(s -> s.zone() == zone).limit(Math.max(1, limit)).toList();
    }

    /** Измеренные клетки зоны по ключу «cx:cy» — для поправки оценки кластеров в «иди качайся». */
    public Map<String, SpotStat> measuredCells(int zone) {
        Map<String, SpotStat> m = new HashMap<>();
        for (SpotStat s : all().spots()) if (s.zone() == zone) m.put(key(s.cx(), s.cy()), s);
        return m;
    }

    public static String key(int cx, int cy) {
        return cx + ":" + cy;
    }

    /** Ключ клетки по координатам карты (0..100). */
    public static String keyFor(double x, double y) {
        return key(SpotModel.cell(x), SpotModel.cell(y));
    }
}
