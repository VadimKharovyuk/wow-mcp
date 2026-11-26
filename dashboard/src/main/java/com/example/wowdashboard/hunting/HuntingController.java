package com.example.wowdashboard.hunting;

import com.example.wowdashboard.common.Periods;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Лучшие места охоты по данным: опыт в час, смерти, упоры. Только чтение. */
@RestController
@RequestMapping("/api/hunting")
class HuntingController {

    private final HuntingService hunting;

    HuntingController(HuntingService hunting) {
        this.hunting = hunting;
    }

    /** Клетки карты от лучшей к худшей. hours — период (по умолчанию 24), zone — зона бота, limit — сколько клеток (по умолчанию 15). */
    @GetMapping("/best")
    HuntingResult best(@RequestParam(required = false) Integer hours, @RequestParam(required = false) Integer zone,
                       @RequestParam(required = false) Integer limit) {
        return hunting.best(Periods.hours(hours), zone, limit == null ? 15 : Math.max(1, Math.min(100, limit)));
    }
}
