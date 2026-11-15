package com.example.wowmcp.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.wowmcp.tools.ToolSupport.JSON;

/** Разбор и проверка ротации, которую присылает клиент в {@code set_rotation}. */
final class RotationParser {

    private RotationParser() {
    }

    /**
     * Валидирует и нормализует ротацию: key '1'..'9' или spell (nameHash), необязательные
     * maxHpPct / maxPetHpPct 0..1, repeatMs, outOfCombatOnly и waitAfter. В страницу передаются уже проверенные
     * значения, без склейки JS-строк.
     */
    static List<Map<String, Object>> parse(String rotationJson) {
        if (rotationJson == null || rotationJson.isBlank()) {
            throw new IllegalArgumentException(
                    "rotationJson не должен быть пустым. Ожидается JSON-массив вида "
                            + "[{\"key\":\"3\",\"maxHpPct\":0.4},{\"key\":\"2\"},{\"key\":\"1\"}]");
        }

        Object parsed;
        try {
            parsed = JSON.readValue(rotationJson, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rotationJson должен быть корректным JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "rotationJson должен быть непустым JSON-массивом объектов вида "
                            + "{\"key\":\"3\",\"maxHpPct\":0.4}");
        }

        List<Map<String, Object>> rotation = new ArrayList<>();
        Set<String> usedIdentities = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            int position = i + 1;
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException(
                        "Элемент #" + position + " должен быть объектом с полем key ('1'..'9') или spell (nameHash)");
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            Object spellValue = item.get("spell");
            Object keyValue = item.get("key");
            if (spellValue != null) {
                if (!(spellValue instanceof Number spellNumber) || spellNumber.doubleValue() != Math.rint(spellNumber.doubleValue())) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": spell должен быть целым nameHash, получено: " + spellValue);
                }
                int spell = spellNumber.intValue();
                if (!usedIdentities.add("spell:" + spell)) {
                    throw new IllegalArgumentException("Заклинание (nameHash " + spell + ") указано в ротации больше одного раза");
                }
                entry.put("spell", spell);
            } else {
                String key = slotKey(keyValue, "key", position);
                if (!usedIdentities.add("key:" + key)) {
                    throw new IllegalArgumentException("Слот " + key + " указан в ротации больше одного раза");
                }
                entry.put("key", key);
            }

            Object maxHpPctValue = item.get("maxHpPct");
            if (maxHpPctValue != null) {
                if (!(maxHpPctValue instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": maxHpPct должен быть числом от 0 до 1, получено: "
                                    + maxHpPctValue);
                }
                double threshold = number.doubleValue();
                if (Double.isNaN(threshold) || threshold < 0 || threshold > 1) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": maxHpPct должен быть от 0 до 1, получено: "
                                    + maxHpPctValue);
                }
                entry.put("maxHpPct", threshold);
            }

            Object maxPetHpPctValue = item.get("maxPetHpPct");
            if (maxPetHpPctValue != null) {
                if (!(maxPetHpPctValue instanceof Number number)
                        || Double.isNaN(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 1) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": maxPetHpPct должен быть числом от 0 до 1, получено: "
                                    + maxPetHpPctValue);
                }
                entry.put("maxPetHpPct", number.doubleValue());
            }

            Object repeatMsValue = item.get("repeatMs");
            if (repeatMsValue != null) {
                if (!(repeatMsValue instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() < 0 || number.doubleValue() > 600_000
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": repeatMs должен быть целым числом от 0 до 600000, получено: "
                                    + repeatMsValue);
                }
                entry.put("repeatMs", number.intValue());
            }

            Object outOfCombatValue = item.get("outOfCombatOnly");
            if (outOfCombatValue != null) {
                if (!(outOfCombatValue instanceof Boolean outOfCombatOnly)) {
                    throw new IllegalArgumentException(
                            "Элемент #" + position + ": outOfCombatOnly должен быть true/false, получено: "
                                    + outOfCombatValue);
                }
                entry.put("outOfCombatOnly", outOfCombatOnly);
            }

            Object waitAfterValue = item.get("waitAfter");
            if (waitAfterValue != null) {
                entry.put("waitAfter", parseWaitAfter(waitAfterValue, position));
            }

            rotation.add(entry);
        }
        return rotation;
    }

    private static Map<String, Object> parseWaitAfter(Object waitAfterValue, int position) {
        if (!(waitAfterValue instanceof Map<?, ?> waitAfter)) {
            throw new IllegalArgumentException(
                    "Элемент #" + position + ": waitAfter должен быть объектом {key, ms}, получено: "
                            + waitAfterValue);
        }
        Object waitMsValue = waitAfter.get("ms");
        if (!(waitMsValue instanceof Number msNumber)) {
            throw new IllegalArgumentException(
                    "Элемент #" + position + ": waitAfter.ms должен быть числом от 0 до 10000, получено: "
                            + waitMsValue);
        }
        double waitMs = msNumber.doubleValue();
        if (!Double.isFinite(waitMs) || waitMs < 0 || waitMs > 10_000 || waitMs != Math.rint(waitMs)) {
            throw new IllegalArgumentException(
                    "Элемент #" + position + ": waitAfter.ms должен быть целым числом от 0 до 10000, "
                            + "получено: " + waitMsValue);
        }
        Object waitSpellValue = waitAfter.get("spell");
        Map<String, Object> waitAfterEntry = new LinkedHashMap<>();
        if (waitSpellValue != null) {
            if (!(waitSpellValue instanceof Number ws) || ws.doubleValue() != Math.rint(ws.doubleValue())) {
                throw new IllegalArgumentException(
                        "Элемент #" + position + ": waitAfter.spell должен быть целым nameHash, получено: "
                                + waitSpellValue);
            }
            waitAfterEntry.put("spell", ws.intValue());
        } else {
            waitAfterEntry.put("key", slotKey(waitAfter.get("key"), "waitAfter.key", position));
        }
        waitAfterEntry.put("ms", (int) waitMs);
        return waitAfterEntry;
    }

    /** Слот панели '1'..'9' из JSON-значения (строка или целое число). */
    private static String slotKey(Object value, String field, int position) {
        String key = value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                ? String.valueOf(number.intValue())
                : String.valueOf(value);
        if (value == null || !key.matches("[1-9]")) {
            throw new IllegalArgumentException(
                    "Элемент #" + position + ": " + field + " должен быть слотом '1'..'9', получено: " + value);
        }
        return key;
    }
}
