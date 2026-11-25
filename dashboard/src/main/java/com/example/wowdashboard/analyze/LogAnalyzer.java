package com.example.wowdashboard.analyze;

import com.example.wowdashboard.digest.DigestSource;
import com.example.wowdashboard.digest.DigestText;
import com.example.wowdashboard.digest.Verdict;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Разбор сводки журнала. Одновременно идёт один разбор (модель тяжёлая); последний результат хранится в памяти. */
@Service
class LogAnalyzer {

    /** Модель пишет только гипотезы по значимым проблемам, отчёт с фактами (работает, что изменилось, проблемы) собирает программа. */
    static final String SYSTEM = """
            Ты помогаешь разбирать журнал бота для WoW 3.3.5 (паладин). Тебе даны значимые проблемы за период, выводы по цифрам
            и тренд по частям периода. Для КАЖДОЙ значимой проблемы напиши одну строку по-русски:
            «проблема — вероятная причина (гипотеза) — что проверить».

            Правила:
            - Пиши только про перечисленные значимые проблемы. Не добавляй других пунктов, не пересказывай «что работает» и не пиши итоги.
            - Не выдумывай числа и факты: используй только числа из сводки. Если причина по сводке неясна, напиши «причина по сводке неясна».
            - Не предлагай проверять соединение, сеть, оборудование, версии и другие общие вещи, которых нет в данных.
            - Улучшение — это стало лучше, ухудшение — стало хуже; не путай. Опирайся на раздел «Выводы по цифрам».
            - Не повторяй один и тот же пункт разными словами. Коротко.

            Как устроен бот (используй для причин и проверок):
            - press_fail «слишком далеко»: умение нажато вне дальности (связано с сближением и подходом к цели).
            - press_fail «не лицом к цели»: цель не перед персонажем (связано с доворотом и поиском атакующего).
            - press_fail «нет реакции» / «другая ошибка игры»: нажатие не принято (каст, откат, общее время восстановления).
            - approach «нет сближения»: бот не приблизился к цели за отведённое время (препятствие, цель недостижима, вне зоны).
            - «поиск атакующего»: после убийства цели бот по Tab ищет того, кто целится в игрока; «не найден» бывает, когда никто уже не бьёт.
            - «доворот к цели вне экрана»: бот вращается в поиске цели, у которой нет полосы здоровья на экране (цель сзади или слишком далеко).
            - travel: перебежки верхом между точками; «сел/не сел» — удалась ли посадка на коня.
            - stuck: упор в препятствие; lua/bridge: связь бота с игрой; числа N в сообщениях заменяют конкретные числа.
            """;

    record Result(String text, String model, long ms, Instant at, int minutes) {
    }

    private final DigestSource digests;
    private final ModelClient modelClient;
    private final AnalyzerProperties props;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Result last;

    LogAnalyzer(DigestSource digests, ModelClient modelClient, AnalyzerProperties props) {
        this.digests = digests;
        this.modelClient = modelClient;
        this.props = props;
    }

    /** Модель из запроса принимается только из списка analyzer.models (или основная); иначе — основная. */
    String resolveModel(String requested) {
        if (requested == null || requested.isBlank()) return props.model();
        return props.models().contains(requested) ? requested : props.model();
    }

    Map<String, Object> config() {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("model", props.model());
        c.put("models", props.models());
        return c;
    }

    Result last() {
        return last;
    }

    boolean isBusy() {
        return busy.get();
    }

    /** @throws IllegalStateException если другой разбор ещё идёт */
    Result analyze(int minutes, String model) {
        String useModel = resolveModel(model);
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("разбор уже идёт");
        try {
            long t0 = System.currentTimeMillis();
            Map<String, Object> d = digests.digest(minutes);
            @SuppressWarnings("unchecked")
            Map<String, Object> verdict = (Map<String, Object>) d.get("verdict");
            String report = Verdict.render(verdict);
            @SuppressWarnings("unchecked")
            List<String> significant = (List<String>) verdict.get("significant");
            if (significant.isEmpty()) {
                report += "\n\nМодель не вызывалась: значимых проблем нет.";
            } else {
                String comment = modelClient.chat(SYSTEM, DigestText.renderForModel(d, props.maxPromptChars()), useModel);
                report += "\n\nГипотезы модели " + useModel + " по значимым проблемам (это догадки, а не факты):\n"
                        + (comment.isBlank() ? "(модель вернула пустой ответ)" : comment);
            }
            Result r = new Result(report, useModel, System.currentTimeMillis() - t0, Instant.now(), minutes);
            last = r;
            return r;
        } finally {
            busy.set(false);
        }
    }
}
