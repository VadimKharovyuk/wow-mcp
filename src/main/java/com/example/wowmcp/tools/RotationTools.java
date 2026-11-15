package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.CastHistoryRepository;
import com.example.wowmcp.db.ProfileRepository;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.learn.RotationLearner;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.ToolSupport.JSON;
import static com.example.wowmcp.util.Values.asInt;

/** MCP-инструменты умений, ротации и профилей: spells, set/get_rotation, set_profile, обучение. */
@Component
public class RotationTools {

    private final GameBridge bridge;
    private final Database db;
    private final ProfileRepository profiles;
    private final CastHistoryRepository casts;
    private final RotationLearner learner;

    public RotationTools(GameBridge bridge, Database db, ProfileRepository profiles, CastHistoryRepository casts, RotationLearner learner) {
        this.bridge = bridge;
        this.db = db;
        this.profiles = profiles;
        this.casts = casts;
        this.learner = learner;
    }

    @Tool(name = "spells", description = """
            Показывает заклинания на панелях: spellId, nameHash, слот, привязанная клавиша, \
            готовность и наличие дальности. Название можно узнать в игре через \
            /run print(GetSpellInfo(<spellId>)).""")
    public String spells() {
        bridge.ensureReady();
        Object raw = bridge.eval("() => JSON.stringify(bot.spells())");
        try {
            return JSON.writeValueAsString(Map.of(
                    "spells", JSON.readValue(String.valueOf(raw), Object.class)));
        } catch (RuntimeException e) {
            return JSON.writeValueAsString(Map.of("spells", raw));
        }
    }

    @Tool(name = "set_rotation", description = """
            Задать приоритет умений: список от самого важного к основному удару. Применяется сразу \
            и сохраняется в БД для активного профиля (переживает перезапуск и перезагрузку страницы). \
            Формат v2 (по nameHash заклинания, как в spells): \
            [{"spell":123,"maxHpPct":0.35},{"spell":456,"waitAfter":{"spell":123,"ms":1000}}]. \
            Старый формат v1 (по слоту панели): [{"key":"2","maxHpPct":0.35},{"key":"4"}]. \
            Поля правила, все необязательные: \
            maxHpPct (0..1) — только если HP игрока ниже этой доли; \
            maxPetHpPct (0..1) — только если HP питомца ниже этой доли; \
            repeatMs — не повторять чаще, чем раз в repeatMs мс; \
            outOfCombatOnly (true/false) — только вне боя; \
            waitAfter {spell|key, ms} — не переходить к следующим умениям, пока с нажатия прошло меньше ms. \
            Макросы и умения без nameHash задаются через key (слот 1–9 панели), например {"key":"9"} — /startattack. \
            Правила с maxHpPct, maxPetHpPct, repeatMs или key автообучение не затирает. \
            Пример: [{"spell":1672233375,"maxHpPct":0.45},{"spell":1700418603,"outOfCombatOnly":true},\
            {"spell":1568831975,"waitAfter":{"spell":1700418603,"ms":2300}},{"spell":2037478445},{"key":"9"}].""")
    public String setRotation(
            @ToolParam(description = "JSON-массив v2: [{\"spell\":123,\"maxPetHpPct\":0.5},"
                    + "{\"spell\":456,\"repeatMs\":15000},{\"spell\":789,\"maxHpPct\":0.35,"
                    + "\"waitAfter\":{\"spell\":123,\"ms\":1000}},{\"spell\":321}]. "
                    + "v1: [{\"key\":\"2\",\"maxHpPct\":0.35},{\"key\":\"1\"}]")
            String rotationJson) {
        List<Map<String, Object>> rotation = RotationParser.parse(rotationJson);
        bridge.ensureReady();
        Object saved = bridge.eval(
                "(r) => { bot.rotation = r; return JSON.stringify(bot.rotation); }", rotation);

        // Сохраняем в БД, иначе после перезагрузки страницы вернётся старая выученная ротация
        String profile = String.valueOf(bridge.eval("() => bot.profile"));
        boolean inDb = false;
        if (db.isEnabled() && !"null".equals(profile) && !profile.isBlank()) {
            Map<String, Object> row = profiles.loadRotationProfile(profile);
            Integer samples = row == null ? null : asInt(row.get("samples"));
            // статистика описывает прежнюю выученную ротацию, к заданной вручную она не относится
            profiles.upsertRotationProfile(profile, JSON.writeValueAsString(rotation),
                    samples == null ? 0 : samples, null);
            inDb = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ротация сохранена");
        result.put("rotation", JSON.readValue(String.valueOf(saved), Object.class));
        result.put("profile", "null".equals(profile) ? null : profile);
        result.put("savedToDb", inDb);
        if (!inDb) {
            result.put("note", "в БД не сохранено: нет профиля (set_profile) или база не настроена — "
                    + "после перезагрузки страницы ротация пропадёт");
        }
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "get_rotation", description = """
            Показывает текущий приоритет умений. Для v2 (spell) дополнительно показывает spellId (если известен), \
            текущую клавишу из spells() (например "O", "2", "Shift+E") и правило; а также статистику обучения.""")
    public String getRotation() {
        bridge.ensureReady();
        Object rotation = bridge.eval("() => JSON.stringify(bot.rotation)");
        String profile = null;
        try {
            profile = String.valueOf(bridge.eval("() => bot.profile"));
        } catch (RuntimeException ignored) {
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> rotationList;
        try {
            rotationList = JSON.readValue(String.valueOf(rotation), new tools.jackson.core.type.TypeReference<>() {});
        } catch (RuntimeException e) {
            result.put("rotation", rotation);
            rotationList = null;
        }
        if (rotationList != null) {
            List<Object> spellList;
            try {
                Object rawSpells = bridge.eval("() => JSON.stringify(bot.spells())");
                spellList = JSON.readValue(String.valueOf(rawSpells), new tools.jackson.core.type.TypeReference<>() {});
            } catch (RuntimeException e) {
                spellList = List.of();
            }
            Map<Integer, Map<String, Object>> byHash = new LinkedHashMap<>();
            for (Object o : spellList) {
                if (o instanceof Map<?, ?> m && m.get("nameHash") instanceof Number n) {
                    byHash.put(n.intValue(), (Map<String, Object>) m);
                }
            }
            for (int idx = 0; idx < rotationList.size(); idx++) {
                Object o = rotationList.get(idx);
                if (!(o instanceof Map<?, ?> rule)) continue;
                Object spellValue = rule.get("spell");
                if (spellValue instanceof Number n) {
                    Map<String, Object> info = byHash.get(n.intValue());
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) rule);
                    if (info != null) {
                        if (info.get("spellId") != null) copy.put("spellId", info.get("spellId"));
                        copy.put("slot", info.get("slot"));
                        copy.put("keyCode", info.get("keyCode"));
                        copy.put("mods", info.get("mods"));
                        copy.put("key", formatSpellKey(info));
                    }
                    rotationList.set(idx, copy);
                }
            }
        }
        result.put("rotation", rotationList);
        result.put("profile", "null".equals(profile) ? null : profile);
        if (db.isEnabled() && profile != null && !"null".equals(profile)) {
            Map<String, Object> row = profiles.loadRotationProfile(profile);
            if (row != null && row.get("stats_json") != null) {
                try {
                    result.put("stats", JSON.readValue(String.valueOf(row.get("stats_json")), Object.class));
                } catch (RuntimeException e) {
                    result.put("stats", row.get("stats_json"));
                }
            } else {
                result.put("stats", null);
            }
            result.put("samples", row == null ? null : row.get("samples"));
        } else {
            result.put("stats", null);
            result.put("samples", null);
        }
        return JSON.writeValueAsString(result);
    }

    private static String formatSpellKey(Map<String, Object> info) {
        if (info == null) return null;
        Integer keyCode = asInt(info.get("keyCode"));
        if (keyCode == null || keyCode == 0) return null;
        Integer mods = asInt(info.get("mods"));
        int m = mods == null ? 0 : mods;
        String base = keyChar(keyCode);
        if (base == null || base.isEmpty()) base = "keyCode:" + keyCode;
        StringBuilder sb = new StringBuilder();
        if ((m & 1) != 0) sb.append("Shift+");
        if ((m & 2) != 0) sb.append("Ctrl+");
        if ((m & 4) != 0) sb.append("Alt+");
        sb.append(base);
        return sb.toString();
    }

    private static String keyChar(int c) {
        if (c >= 65 && c <= 90) return String.valueOf((char) c);
        if (c >= 48 && c <= 57) return String.valueOf((char) c);
        if (c == 189) return "-";
        if (c == 187) return "=";
        if (c >= 112 && c <= 123) return "F" + (c - 111);
        if (c == 32) return "Space";
        return null;
    }

    @Tool(name = "set_profile", description = """
            Задать активный профиль персонажа (имя). Бот начинает записывать нажатия игрока \
            для этого профиля и загружает сохранённую ротацию (если есть). \
            Профиль сохраняется в БД (active_profile) и переживает перезапуск сервера \
            и перезагрузку страницы. \
            Без БД работает в памяти: нажатия не сохраняются, обучение недоступно.""")
    public String setProfile(
            @ToolParam(description = "Имя профиля (например paladin, mage)") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name не должен быть пустым");
        }
        bridge.ensureReady();
        bridge.eval("(p) => bot.setProfile ? bot.setProfile(p) : (bot.profile = p, window.__wowProfile = p)", name);
        Map<String, Object> loaded = null;
        if (db.isEnabled()) {
            profiles.setActiveProfile(name);   // чтобы autoLoadProfile нашёл профиль после перезапуска
            loaded = profiles.loadRotationProfile(name);
            if (loaded != null && loaded.get("rotation_json") != null) {
                Object rotation = JSON.readValue(String.valueOf(loaded.get("rotation_json")), Object.class);
                bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", rotation);
            }
            profiles.touchProfile(name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "профиль установлен: " + name);
        result.put("profile", name);
        result.put("rotationLoaded", loaded != null && loaded.get("rotation_json") != null);
        result.put("dbEnabled", db.isEnabled());
        if (!db.isEnabled()) {
            result.put("note", "база не настроена — нажатия не сохраняются, обучение недоступно");
        }
        return JSON.writeValueAsString(result);
    }

    @Tool(name = "learn_rotation", description = """
            Принудительно переучить ротацию активного профиля. Если есть данные spell_cast — учится только по ним (v2, nameHash); \
            fallback на key_press только когда spell_cast пуст. При недостатке данных spell_cast текущая ротация не меняется.""")
    public String learnRotation() {
        bridge.ensureReady();
        String profile = String.valueOf(bridge.eval("() => bot.profile"));
        if ("null".equals(profile) || profile.isBlank()) {
            return JSON.writeValueAsString(Map.of("result", "профиль не задан — сначала set_profile"));
        }
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена — обучение недоступно",
                    "dbEnabled", false));
        }

        // Текущая ротация нужна для сохранения аварийных правил (maxHpPct) при малом числе данных
        List<Map<String, Object>> currentRotation = List.of();
        try {
            Object raw = bridge.eval("() => JSON.stringify(bot.rotation)");
            currentRotation = JSON.readValue(String.valueOf(raw), new tools.jackson.core.type.TypeReference<>() {});
        } catch (RuntimeException ignored) {
        }

        List<Map<String, Object>> spellRows = casts.querySpellCasts(profile);
        if (!spellRows.isEmpty()) {
            RotationLearner.SpellLearn attempt = learner.learnSpells(profile, currentRotation);
            if (attempt.isInsufficient()) {
                return JSON.writeValueAsString(Map.of(
                        "result", "мало данных: " + attempt.inCombatCasts() + " применений в бою, нужно " + attempt.requiredInCombat(),
                        "profile", profile,
                        "inCombatCasts", attempt.inCombatCasts(),
                        "requiredInCombat", attempt.requiredInCombat(),
                        "rotationKept", true));
            }
            RotationLearner.Result res = attempt.result();
            if (res == null) {
                return JSON.writeValueAsString(Map.of(
                        "result", "мало данных: " + attempt.inCombatCasts() + " применений в бою, нужно " + attempt.requiredInCombat(),
                        "profile", profile,
                        "inCombatCasts", attempt.inCombatCasts(),
                        "requiredInCombat", attempt.requiredInCombat(),
                        "rotationKept", true));
            }
            String rotJson = JSON.writeValueAsString(res.rotation());
            bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
            String statsJson = res.stats() == null ? null : JSON.writeValueAsString(res.stats());
            profiles.upsertRotationProfile(profile, rotJson, res.samples(), statsJson);
            learner.setLastLearnedCount(profile, spellRows.size());
            return JSON.writeValueAsString(Map.of("result", "ротация выучена и применена", "profile", profile,
                    "rotation", res.rotation(), "samples", res.samples(), "source", "spell_cast"));
        }

        // Fallback на key_press только если spell_cast пуст
        RotationLearner.Result res = learner.learn(profile);
        if (res == null) {
            return JSON.writeValueAsString(Map.of("result", "недостаточно данных для обучения", "profile", profile));
        }
        String rotJson = JSON.writeValueAsString(res.rotation());
        bridge.eval("(r) => { bot.rotation = r; return 'ok'; }", res.rotation());
        String statsJson = res.stats() == null ? null : JSON.writeValueAsString(res.stats());
        profiles.upsertRotationProfile(profile, rotJson, res.samples(), statsJson);
        learner.setLastLearnedCount(profile, casts.queryKeyPresses(profile).size());
        return JSON.writeValueAsString(Map.of("result", "ротация выучена и применена", "profile", profile,
                "rotation", res.rotation(), "samples", res.samples(), "source", "key_press"));
    }

    @Tool(name = "reset_learning", description = """
            УДАЛЯЕТ все нажатия и выученную ротацию профиля (необратимо).""")
    public String resetLearning(
            @ToolParam(description = "Имя профиля") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name не должен быть пустым");
        }
        if (!db.isEnabled()) {
            // В памяти: просто сбросить профиль, если активен
            bridge.ensureReady();
            String active = String.valueOf(bridge.eval("() => bot.profile"));
            if (name.equals(active)) {
                bridge.eval("() => (bot.setProfile ? bot.setProfile(null) : (bot.profile = null, window.__wowProfile = null))");
            }
            return JSON.writeValueAsString(Map.of("result", "профиль сброшен (база не настроена)", "profile", name,
                    "dbEnabled", false));
        }
        profiles.deleteProfileData(name);
        bridge.ensureReady();
        String active = String.valueOf(bridge.eval("() => bot.profile"));
        if (name.equals(active)) {
            bridge.eval("() => (bot.setProfile ? bot.setProfile(null) : (bot.profile = null, window.__wowProfile = null))");
        }
        return JSON.writeValueAsString(Map.of("result", "данные профиля удалены", "profile", name));
    }
}
