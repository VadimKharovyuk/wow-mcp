package com.example.wowdashboard.digest;

import com.example.wowdashboard.stats.StatsRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Собирает сводку журнала бота за период из запросов {@link DigestRepository} и чистых расчётов ({@link Parts}, {@link Rates},
 * {@link Flags}, {@link Verdict}). Порядок ключей фиксирован: он же порядок в JSON-экспорте.
 */
@Service
public class DigestService implements DigestSource {

    private static final String ABOUT = "Сводка журнала бота WoW (bot_log) за период. Числа в сообщениях заменены на N. "
            + "category: fight, approach, loot, buff, death, job, vendor, press_fail, rest, travel, stuck, lua, bridge. "
            + "Время — часовой пояс БД.";

    private final DigestRepository repo;
    private final StatsRepository stats;

    DigestService(DigestRepository repo, StatsRepository stats) {
        this.repo = repo;
        this.stats = stats;
    }

    @Override
    public Map<String, Object> digest(int minutes) {
        Map<String, Object> d = new LinkedHashMap<>();
        String now = repo.now();
        d.put("about", ABOUT);
        d.put("minutes", minutes);
        d.put("now", now);
        d.put("profiles", repo.profiles(minutes));
        d.put("counts", repo.counts(minutes));
        d.put("noise", Map.of("spellNotOnBars", repo.spellNotOnBars(minutes)));
        d.put("problems", repo.problems(minutes));
        d.put("normal", repo.normal(minutes));
        d.put("newInPeriod", repo.newInPeriod(minutes));
        d.put("fights", repo.fights(minutes));
        d.put("fightResults", repo.fightResults(minutes));
        d.put("switching", repo.switching(minutes));
        d.put("approachFails", repo.approachFails(minutes));
        d.put("travel", repo.travel(minutes));
        d.put("deaths", repo.deaths(minutes));
        d.put("luaAndBridge", repo.luaAndBridge(minutes));
        List<Map<String, Object>> parts = Parts.build(repo.partRows(minutes), minutes, now);
        d.put("parts", parts);
        d.put("rates", Rates.compute(parts, minutes));
        d.put("flags", Flags.compute(parts));
        d.put("game", game(minutes));
        d.put("verdict", Verdict.build(d));
        return d;
    }

    /** Итоги игры за точный период; время работы ограничено периодом (в bot_job бывают незавершённые задачи). */
    private Map<String, Object> game(int minutes) {
        Map<String, Object> game = new LinkedHashMap<>(stats.summaryMinutes(minutes));
        if (game.get("botSeconds") instanceof Number n) game.put("botSeconds", Math.min(n.longValue(), (long) minutes * 60));
        return game;
    }

    /** Полная текстовая сводка для страницы. */
    public String text(int minutes) {
        return DigestText.render(digest(minutes), DigestText.DEFAULT_MAX_CHARS);
    }
}
