package com.example.wowmcp.util;

/** Мягкое приведение значений из JSON/JDBC (Number, String, Boolean) к нужному типу; при неудаче — null (или 0 для long). */
public final class Values {

    private Values() {
    }

    public static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    public static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    public static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    public static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    /** true/false, 1/0, t/f (так отдаёт PostgreSQL); иначе null. */
    public static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "t".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "f".equals(s)) return false;
        return null;
    }
}
