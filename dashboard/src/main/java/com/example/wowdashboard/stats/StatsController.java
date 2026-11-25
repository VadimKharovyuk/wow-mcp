package com.example.wowdashboard.stats;

import com.example.wowdashboard.common.Periods;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** JSON API статистики. Все методы только читают. */
@RestController
@RequestMapping("/api")
class StatsController {

    private final StatsRepository stats;

    StatsController(StatsRepository stats) {
        this.stats = stats;
    }

    @GetMapping("/summary")
    Map<String, Object> summary(@RequestParam(required = false) Integer hours) {
        return stats.summary(Periods.hours(hours));
    }

    @GetMapping("/timeline")
    List<Map<String, Object>> timeline(@RequestParam(required = false) Integer hours) {
        return stats.timeline(Periods.hours(hours));
    }

    @GetMapping("/progress")
    List<Map<String, Object>> progress(@RequestParam(required = false) Integer hours) {
        return stats.progress(Periods.hours(hours));
    }

    @GetMapping("/jobs")
    List<Map<String, Object>> jobs(@RequestParam(required = false) Integer limit) {
        return stats.jobs(limit == null ? 30 : Math.max(1, Math.min(200, limit)));
    }

    @GetMapping("/spots")
    List<Map<String, Object>> spots(@RequestParam(required = false) Integer hours,
                                    @RequestParam(required = false) Integer zone) {
        return stats.spots(Periods.hours(hours), zone);
    }
}
