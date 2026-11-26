package com.example.wowdashboard.advisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Советник бота: предложенные правила и места упоров. Только чтение. */
@RestController
@RequestMapping("/api/advisor")
class AdvisorController {

    private final AdvisorRepository repo;

    AdvisorController(AdvisorRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    Map<String, Object> advisor() {
        List<Map<String, Object>> directives = repo.directives(40);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directives", directives);
        out.put("stuckPoints", repo.stuckPoints(15));
        out.put("active", directives.stream().filter(d -> Boolean.TRUE.equals(d.get("active"))).count());
        return out;
    }
}
