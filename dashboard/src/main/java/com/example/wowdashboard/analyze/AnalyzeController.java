package com.example.wowdashboard.analyze;

import com.example.wowdashboard.common.Periods;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Разбор сводки журнала моделью. Только чтение БД; сама сводка и экспорт JSON живут в digest.DigestController. */
@RestController
@RequestMapping("/api")
class AnalyzeController {

    private final LogAnalyzer analyzer;

    AnalyzeController(LogAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /** Разбор моделью (Ollama). Занимает до нескольких минут. */
    @PostMapping("/analyze")
    ResponseEntity<Map<String, Object>> analyze(@RequestParam(required = false) Integer minutes,
                                                @RequestParam(required = false) String model) {
        try {
            return ResponseEntity.ok(toMap(analyzer.analyze(Periods.minutes(minutes), model)));
        } catch (IllegalStateException busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", busy.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error",
                    "Ollama недоступна или модель не ответила: " + e.getMessage() + ". Проверьте, что Ollama запущена и модель скачана."));
        }
    }

    /** Какие модели можно выбрать на странице. */
    @GetMapping("/analyzer")
    Map<String, Object> config() {
        return analyzer.config();
    }

    /** Последний разбор из памяти процесса (после перезапуска пусто). */
    @GetMapping("/analyze/last")
    Map<String, Object> last() {
        LogAnalyzer.Result r = analyzer.last();
        Map<String, Object> m = r == null ? new LinkedHashMap<>() : toMap(r);
        m.put("busy", analyzer.isBusy());
        return m;
    }

    private static Map<String, Object> toMap(LogAnalyzer.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", r.text());
        m.put("model", r.model());
        m.put("ms", r.ms());
        m.put("at", r.at().toString());
        m.put("minutes", r.minutes());
        return m;
    }
}
