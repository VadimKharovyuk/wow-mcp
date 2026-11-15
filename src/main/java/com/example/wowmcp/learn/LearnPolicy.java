package com.example.wowmcp.learn;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Правила обучения ротации: какие заклинания не учить, какие правила не затирать
 * и для каких профилей автообучение выключено.
 *
 * <p>Автообучение раньше записывало в ротацию переключатель «Автоматическая стрельба»
 * (повторное нажатие ВЫКЛЮЧАЕТ стрельбу), «Атаку», «Кормление питомца» и «Лечение питомца»
 * без условия. Теперь эти заклинания из обучения исключены, а правила с условиями
 * ({@code maxHpPct}, {@code maxPetHpPct}, {@code repeatMs}) или слотом ({@code key}),
 * заданные вручную, при перезаписи сохраняются.
 *
 * <p>Автообучение можно выключить для профилей: {@code wow.learn.disabled-profiles: hunter}.
 */
@Component
public class LearnPolicy {

    /**
     * Заклинания, которые никогда не попадают в выученную ротацию (русские названия клиента).
     * Лечение питомца исключено из обучения: оно нужно только с условием maxPetHpPct,
     * поэтому его задают вручную через set_rotation.
     */
    private static final List<String> EXCLUDED_NAMES = List.of(
            "Автоматическая стрельба", // 75
            "Атака",                   // 6603
            "Кормление питомца",       // 6991
            "Лечение питомца",         // 136
            "Замораживающая ловушка",  // ставится по ситуации (аварийно), а не по ротации
            "Притвориться мёртвым");   // последнее средство при HP < 15%, не часть ротации

    private static final Set<Integer> EXCLUDED_HASHES = new HashSet<>();

    static {
        for (String name : EXCLUDED_NAMES) {
            EXCLUDED_HASHES.add(nameHash(name));
        }
    }

    /**
     * spellId служебных заклинаний, которые не учим: Автоматическая стрельба, Атака, Кормление питомца,
     * Призыв и Отпустить питомца, Лечение питомца. Работает и без знания nameHash (spell_id пишется в spell_cast).
     */
    private static final Set<Integer> EXCLUDED_SPELL_IDS = Set.of(75, 6603, 6991, 883, 2641, 136);

    private final Set<String> disabledProfiles;
    private final Set<Integer> excludedSpellIds;

    @Autowired
    public LearnPolicy(@Value("${wow.learn.disabled-profiles:}") List<String> disabledProfiles,
                       @Value("${wow.learn.excluded-spell-ids:}") List<Integer> extraExcludedSpellIds) {
        Set<String> set = new HashSet<>();
        for (String p : disabledProfiles) {
            if (p != null && !p.isBlank()) set.add(p.trim());
        }
        this.disabledProfiles = Set.copyOf(set);
        Set<Integer> ids = new HashSet<>(EXCLUDED_SPELL_IDS);
        ids.addAll(extraExcludedSpellIds);
        this.excludedSpellIds = Set.copyOf(ids);
    }

    public LearnPolicy(List<String> disabledProfiles) {
        this(disabledProfiles, List.of());
    }

    /** Исключено ли заклинание по spellId (из spell_cast.spell_id). */
    public boolean isExcludedSpellId(Integer spellId) {
        return spellId != null && excludedSpellIds.contains(spellId);
    }

    /** nameHash локализованного названия: h = (h*31 + байт UTF-8) mod 2147483647. */
    static int nameHash(String name) {
        long h = 0;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            h = (h * 31 + (b & 0xFF)) % 2147483647L;
        }
        return (int) h;
    }

    /** Исключено ли заклинание из обучения. */
    public boolean isExcluded(Integer nameHash) {
        return nameHash != null && EXCLUDED_HASHES.contains(nameHash);
    }

    /** Включено ли автообучение для профиля (ручной learn_rotation работает всегда). */
    public boolean isAutoLearnEnabled(String profile) {
        return profile != null && !disabledProfiles.contains(profile);
    }

    /**
     * Правило задано вручную и не должно затираться обучением: у него есть условие
     * ({@code maxHpPct}, {@code maxPetHpPct}, {@code repeatMs}) или оно привязано к слоту ({@code key}).
     * Правила, которые создало само обучение, помечены {@code learned: true} и пересчитываются.
     */
    static boolean isManualRule(Map<String, Object> rule) {
        if (rule == null || Boolean.TRUE.equals(rule.get("learned"))) return false;
        return rule.get("maxHpPct") != null
                || rule.get("maxPetHpPct") != null
                || rule.get("repeatMs") != null
                || rule.get("key") != null;
    }
}
