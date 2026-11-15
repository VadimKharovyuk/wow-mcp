package com.example.wowmcp.tools;

import java.util.Locale;

/** Имена мест и маршрутов: нижний регистр, без «ё», без лишних пробелов. */
final class Names {

    private Names() {
    }

    static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
