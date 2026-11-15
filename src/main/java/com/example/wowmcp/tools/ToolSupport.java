package com.example.wowmcp.tools;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Общие помощники MCP-инструментов: JSON, проверка координат, аргумент торговца. */
final class ToolSupport {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private ToolSupport() {
    }

    /** {@code {"result": text}}. */
    static String jsonResult(String text) {
        return JSON.writeValueAsString(Map.of("result", text));
    }

    static void validateCoordinates(double x, double y) {
        if (Double.isNaN(x) || x < 0 || x > 100) {
            throw new IllegalArgumentException(
                    "x должен быть числом от 0 до 100 (координаты зоны), получено: " + x);
        }
        if (Double.isNaN(y) || y < 0 || y > 100) {
            throw new IllegalArgumentException(
                    "y должен быть числом от 0 до 100 (координаты зоны), получено: " + y);
        }
    }

    static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " не должен быть пустым");
        }
    }

    /** Запись торговца из БД → аргумент bot.sellAt (facing может отсутствовать в старых записях). */
    static Map<String, Object> vendorArg(Map<String, Object> v) {
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("x", ((Number) v.get("x")).doubleValue());
        vendor.put("y", ((Number) v.get("y")).doubleValue());
        vendor.put("name", String.valueOf(v.get("name")));
        Object facing = v.get("facing");
        if (facing instanceof Number n) {
            vendor.put("facing", n.doubleValue());
        }
        return vendor;
    }
}
