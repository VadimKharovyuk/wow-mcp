package com.example.wowdashboard.digest;

import com.example.wowdashboard.common.Periods;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Сводка журнала бота: JSON (он же экспорт) и текст. Только чтение. */
@RestController
@RequestMapping("/api/digest")
class DigestController {

    private final DigestService digests;

    DigestController(DigestService digests) {
        this.digests = digests;
    }

    /** Сводка в JSON (основные признаки для анализа). download=1 — отдать файлом. */
    @GetMapping
    ResponseEntity<Map<String, Object>> digest(@RequestParam(required = false) Integer minutes,
                                               @RequestParam(required = false) Integer download) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (download != null && download == 1) b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wow-digest.json\"");
        return b.body(digests.digest(Periods.minutes(minutes)));
    }

    /** Та же сводка текстом. */
    @GetMapping(value = "/text", produces = "text/plain;charset=UTF-8")
    String text(@RequestParam(required = false) Integer minutes) {
        return digests.text(Periods.minutes(minutes));
    }
}
