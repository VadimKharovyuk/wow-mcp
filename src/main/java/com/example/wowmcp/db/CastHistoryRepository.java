package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** История действий игрока для обучения ротации: применения заклинаний (spell_cast) и нажатия клавиш (key_press). */
@Component
public class CastHistoryRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(CastHistoryRepository.class);

    public CastHistoryRepository(Database db) {
        super(db);
    }

    public void insertKeyPress(String profile, String key, Double hp, Double mana, Integer ready, Boolean fight,
                               Boolean target, Double targetHpPct, Integer sinceFightMs, String prevKey,
                               Integer prevKeyMs, long atMillis) {
        if (!isEnabled()) return;
        String prof = normalizeProfile(profile);
        try {
            jdbc().update(
                    "INSERT INTO key_press (profile,key,hp,mana,ready,fight,target,target_hp_pct,since_fight_ms,prev_key,prev_key_ms,pressed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    prof, key, hp, mana, ready, fight, target, targetHpPct, sinceFightMs, prevKey, prevKeyMs,
                    new Timestamp(atMillis));
            db.insertSucceeded();
        } catch (Exception e) {
            db.insertFailed("нажатие клавиши", e);
        }
    }

    /**
     * Записывает применение заклинания игроком. Возвращает true, если строка действительно вставлена
     * (по этому счётчику EventCollector пишет лог «вставлено cast=K»).
     */
    public boolean insertSpellCast(String profile, int nameHash, Integer spellId, Double hp, Double mana, Boolean fight,
                                   Boolean target, Double targetHpPct, List<Integer> readySpells, Integer sinceFightMs,
                                   Integer prevSpell, Integer prevSpellMs, long atMillis) {
        if (!isEnabled()) return false;
        String prof = normalizeProfile(profile);
        try {
            // ready_spells передаём текстовым литералом массива и кастуем в int4[]:
            // так не нужно тащить java.sql.Array из чужого соединения пула.
            jdbc().update(
                    "INSERT INTO spell_cast (profile,name_hash,spell_id,hp,mana,fight,target,target_hp_pct,ready_spells,since_fight_ms,prev_spell,prev_spell_ms,cast_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,CAST(? AS int4[]),?,?,?,?)",
                    prof, nameHash, spellId, hp, mana, fight, target, targetHpPct, intArrayLiteral(readySpells),
                    sinceFightMs, prevSpell, prevSpellMs, new Timestamp(atMillis));
            db.insertSucceeded();
            return true;
        } catch (Exception e) {
            db.insertFailed("применение заклинания", e);
            return false;
        }
    }

    /** {@code [1,2,3] -> "{1,2,3}"}; пустой/отсутствующий список -> null (колонка nullable). */
    static String intArrayLiteral(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.append('}').toString();
    }

    private static String normalizeProfile(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile;
    }

    /** Применения заклинаний профиля за последние 14 дней. */
    public List<Map<String, Object>> querySpellCasts(String profile) {
        if (!isEnabled() || profile == null) return List.of();
        try {
            return jdbc().queryForList(
                    "SELECT name_hash, spell_id, hp, mana, fight, target, target_hp_pct, ready_spells, "
                            + "since_fight_ms, prev_spell, prev_spell_ms, cast_at "
                            + "FROM spell_cast WHERE profile = ? AND cast_at >= (now() - interval '14 days') ORDER BY cast_at",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось прочитать применения заклинаний: {}", e.getMessage());
            return List.of();
        }
    }

    /** Нажатия профиля за последние 14 дней. */
    public List<Map<String, Object>> queryKeyPresses(String profile) {
        if (!isEnabled() || profile == null) return List.of();
        try {
            return jdbc().queryForList(
                    "SELECT key, hp, mana, ready, fight, target, target_hp_pct, since_fight_ms, prev_key, prev_key_ms, pressed_at "
                            + "FROM key_press WHERE profile = ? AND pressed_at >= (now() - interval '14 days') ORDER BY pressed_at",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось прочитать нажатия: {}", e.getMessage());
            return List.of();
        }
    }

    /** Дописывает spell_id в старые строки, где он остался NULL. Возвращает число обновлённых строк. */
    public int backfillSpellId(int nameHash, int spellId) {
        if (!isEnabled()) return 0;
        try {
            return jdbc().update("UPDATE spell_cast SET spell_id = ? WHERE name_hash = ? AND spell_id IS NULL",
                    spellId, nameHash);
        } catch (Exception e) {
            log.warn("Не удалось дописать spell_id: {}", e.getMessage());
            return 0;
        }
    }
}
