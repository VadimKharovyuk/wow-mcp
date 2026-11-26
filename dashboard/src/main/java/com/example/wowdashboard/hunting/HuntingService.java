package com.example.wowdashboard.hunting;

import org.springframework.stereotype.Service;

import java.util.List;

/** Лучшие места охоты за период: загружает данные и отдаёт их чистой модели {@link HuntingModel}. */
@Service
public class HuntingService {

    private final HuntingRepository repo;

    HuntingService(HuntingRepository repo) {
        this.repo = repo;
    }

    /**
     * @param hours период назад от сейчас
     * @param zone  только эта зона бота (null — все)
     * @param limit сколько клеток вернуть
     */
    public HuntingResult best(int hours, Integer zone, int limit) {
        HuntingResult r = HuntingModel.compute(repo.snapshots(hours), repo.kills(hours), repo.deaths(hours), repo.stucks(hours), null);
        List<SpotStat> spots = r.spots().stream().filter(s -> zone == null || s.zone() == zone).limit(Math.max(1, limit)).toList();
        return new HuntingResult(r.level(), r.windows(), r.hours(), r.avgXpPerHour(), spots);
    }
}
