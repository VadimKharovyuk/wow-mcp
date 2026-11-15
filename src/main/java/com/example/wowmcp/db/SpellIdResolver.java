package com.example.wowmcp.db;

import com.example.wowmcp.game.GameBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.wowmcp.util.Values.asInt;

/**
 * Находит spellId по nameHash заклинания (по таблице {@code bot.spells()} из игры), чтобы
 * {@code spell_cast.spell_id} не оставался NULL. Для каждой новой пары дописывает spell_id и в
 * старые строки. Таблицу перечитывает не чаще раза в {@value #REFRESH_INTERVAL_MS} мс.
 */
@Component
public class SpellIdResolver {

    private static final Logger log = LoggerFactory.getLogger(SpellIdResolver.class);

    static final long REFRESH_INTERVAL_MS = 30_000;

    private final GameBridge bridge;
    private final CastHistoryRepository casts;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, Integer> known = new ConcurrentHashMap<>();
    private volatile long lastRefreshAt = 0;

    public SpellIdResolver(GameBridge bridge, CastHistoryRepository casts) {
        this.bridge = bridge;
        this.casts = casts;
    }

    /** spellId заклинания или null, если его нет на панелях. */
    public Integer resolve(Integer nameHash) {
        if (nameHash == null || nameHash == 0) return null;
        Integer id = known.get(nameHash);
        if (id != null) return id;
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt >= REFRESH_INTERVAL_MS) {
            lastRefreshAt = now;
            refresh();
        }
        return known.get(nameHash);
    }

    private void refresh() {
        Optional<String> raw = bridge.evalIfReady("JSON.stringify(bot.spells())", 2000);
        if (raw.isEmpty() || raw.get().isBlank()) return;
        try {
            List<Map<String, Object>> spells = json.readValue(raw.get(), new TypeReference<>() {});
            for (Map<String, Object> s : spells) {
                Integer hash = asInt(s.get("nameHash"));
                Integer spellId = asInt(s.get("spellId"));
                if (hash == null || spellId == null || spellId == 0) continue;
                if (known.put(hash, spellId) == null) {
                    int updated = casts.backfillSpellId(hash, spellId);
                    if (updated > 0) {
                        log.info("spell_cast: дописан spell_id {} для nameHash {} ({} строк)", spellId, hash, updated);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Не удалось разобрать bot.spells(): {}", e.getMessage());
        }
    }
}
