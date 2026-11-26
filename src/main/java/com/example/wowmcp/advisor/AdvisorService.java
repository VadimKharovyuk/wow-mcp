package com.example.wowmcp.advisor;

import com.example.wowmcp.db.BotLogRepository;
import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.LeaderLock;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.spots.SpotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Советник по правилам, раз в 5 минут (только лидер): собирает сигналы (смерти, зависшие бои, точки упора, измеренные клетки), получает
 * правила из {@link AdvisorRules} и сохраняет их в таблицу advisor_directive. Режим {@code wow.advisor.mode}: {@code show} (по умолчанию) —
 * правила только предлагаются и видны в дашборде, бот их не получает; {@code apply} — действующие правила раз в минуту передаются боту
 * ({@code bot.applyDirectives}: запреты и штрафы клеток, точки упора), «иди качайся» их тоже учитывает; {@code off} — выключен.
 * Каждое новое правило пишется в журнал бота (категория advisor).
 */
@Component
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);

    private final AdvisorRepository repo;
    private final SpotService spots;
    private final LeaderLock leader;
    private final Database db;
    private final BotLogRepository botLog;
    private final GameBridge bridge;
    private final String mode;
    private volatile boolean clearedOnce;

    static final ObjectMapper JSON = JsonMapper.builder().build();

    public AdvisorService(AdvisorRepository repo, SpotService spots, LeaderLock leader, Database db, BotLogRepository botLog, GameBridge bridge,
                          @Value("${wow.advisor.mode:show}") String mode) {
        this.repo = repo;
        this.spots = spots;
        this.leader = leader;
        this.db = db;
        this.botLog = botLog;
        this.bridge = bridge;
        this.mode = normalize(mode);
    }

    /** Допустимые режимы: off, show и apply; всё остальное — show (безопасный: правила только предлагаются). */
    static String normalize(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        return "off".equals(m) || "apply".equals(m) ? m : "show";
    }

    public String mode() {
        return mode;
    }

    @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 120_000L)
    public void run() {
        if ("off".equals(mode) || !db.isEnabled() || !leader.isLeader()) return;
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("Советник: ошибка прохода: {}", e.getMessage());
        }
    }

    /** Один проход; возвращает число НОВЫХ правил. */
    int runOnce() {
        Signals signals = new Signals(repo.deathCells(2), repo.stalledFightCells(30), repo.reliableStuck(AdvisorRules.MIN_STUCK), spots.all().spots());
        List<Directive> proposals = AdvisorRules.evaluate(signals);
        int created = 0;
        for (Directive d : proposals) {
            if (repo.upsertDirective(d, "proposed")) {
                created++;
                botLog.insert(System.currentTimeMillis(), null, "info", "advisor",
                        "советник предлагает: " + d.kind() + " (" + d.x() + ", " + d.y() + ") " + d.reason() + ", " + d.ttlMinutes() + " мин",
                        d.zone(), d.x(), d.y(), null, null);
            }
        }
        repo.purgeOld();
        if (created > 0) log.info("Советник: новых правил {} (режим {})", created, mode);
        return created;
    }

    /**
     * Раз в минуту (только лидер, режим apply): отдать боту действующие правила; бот заменяет ими прежние и сам проверяет границы и срок.
     * Передача повторяется, чтобы правила вернулись после перезагрузки бота. Не в режиме apply один раз очищает правила бота (если он
     * получил их при прежнем режиме).
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 150_000L)
    public void sync() {
        if ("off".equals(mode) || !db.isEnabled() || !leader.isLeader()) return;
        try {
            if (!bridge.isBotLoadedIfConnected() || bridge.isBusy()) return;
            if (!"apply".equals(mode)) {
                if (!clearedOnce && bridge.evalIfReady("bot.applyDirectives([])", 2000).isPresent()) clearedOnce = true;
                return;
            }
            List<ActiveDirective> active = repo.activeDirectives();
            Optional<String> r = bridge.evalIfReady("bot.applyDirectives(" + toJson(active) + ")", 2000);
            if (r.isPresent() && !active.isEmpty()) repo.markApplied();
        } catch (Exception e) {
            log.warn("Советник: не удалось передать правила боту: {}", e.getMessage());
        }
    }

    /** Действующие правила зоны для «иди качайся»; вне режима apply — пусто (правила только предлагаются). */
    public List<ActiveDirective> activeFor(int zone) {
        if (!"apply".equals(mode)) return List.of();
        return repo.activeDirectives().stream().filter(d -> d.zone() == zone).toList();
    }

    /** JSON-массив правил для {@code bot.applyDirectives}: вид, зона, координаты, радиус и срок (миллисекунды эпохи). */
    static String toJson(List<ActiveDirective> ds) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ActiveDirective d : ds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", d.kind());
            m.put("zone", d.zone());
            m.put("x", d.x());
            m.put("y", d.y());
            m.put("r", d.r());
            m.put("until", d.untilMs());
            rows.add(m);
        }
        return JSON.writeValueAsString(rows);
    }
}
