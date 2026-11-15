package com.example.wowmcp.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Подготовка Lua-кода к отправке в чат WoW: снятие комментариев, сжатие пробелов,
 * проверка на ASCII и нарезка на куски. Чистые функции, без зависимостей.
 */
final class LuaMinifier {

    /** Чанк Lua-кода для одной строки чата WoW: ≤ 215 БАЙТ (лимит строки чата — 255 БАЙТ, обёртка /run … [==[ … ]==] ~35 символов). */
    static final int LUA_CHUNK_SIZE = 215;

    private LuaMinifier() {
    }

    /**
     * Чистит Lua-исходник под отправку одной строки в чат WoW: снимает комментарии,
     * сжимает пробелы и убирает пробелы вокруг операторов/разделителей вне строковых
     * литералов "..." и '...', затем режет на куски.
     */
    static List<String> splitLuaChunks(String source) {
        String cleaned = compressLua(source);
        if (cleaned.isEmpty()) return List.of();
        return chunk(cleaned);
    }

    /** Проверяет, что код чистый ASCII, и режет на куски по {@link #LUA_CHUNK_SIZE}. */
    static List<String> chunk(String compressed) {
        compressed.codePoints().forEach(cp -> {
            if (cp > 127) {
                throw new IllegalStateException(
                        "Lua-исходник содержит не-ASCII символы (U+" + cp + "): отправка в чат WoW разрешена только для чистого ASCII");
            }
        });
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < compressed.length(); ) {
            int end = Math.min(i + LUA_CHUNK_SIZE, compressed.length());
            chunks.add(compressed.substring(i, end));
            i = end;
        }
        return chunks;
    }

    /**
     * Удаляет комментарии, схлопывает пробельные последовательности в один пробел и убирает
     * пробелы вокруг = , ( ) { } [ ] + - * / < > ~ только вне строковых литералов "..." и '...'.
     * Не создаёт вне строк последовательности "--", "[[" и "]]", которых не было в исходнике.
     */
    static String compressLua(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(source.length());
        char stringQuote = 0;
        boolean escaped = false;
        int len = source.length();
        for (int i = 0; i < len; i++) {
            char c = source.charAt(i);
            if (stringQuote != 0) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == stringQuote) {
                    stringQuote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                stringQuote = c;
                sb.append(c);
                continue;
            }
            if (c == '-' && i + 1 < len && source.charAt(i + 1) == '-') {
                // однострочный комментарий (вне строки) — пропускаем до конца строки
                while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
                if (i < len && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                continue;
            }
            sb.append(c);
        }
        // убираем пробелы вокруг символов (вне строк)
        return minifySpacesOutsideStrings(sb.toString().trim());
    }

    private static boolean isTightChar(char c) {
        return "=,(){}[]+-*/<>~".indexOf(c) >= 0;
    }

    private static boolean wouldCreateForbiddenPair(char prevC, char nextC) {
        return (prevC == '-' && nextC == '-')
                || (prevC == '[' && nextC == '[')
                || (prevC == ']' && nextC == ']');
    }

    private static String minifySpacesOutsideStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char stringQuote = 0;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (stringQuote != 0) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == stringQuote) {
                    stringQuote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                stringQuote = c;
                out.append(c);
                continue;
            }
            if (c == ' ') {
                int prev = out.length() - 1;
                char prevC = prev >= 0 ? out.charAt(prev) : 0;
                int j = i + 1;
                while (j < s.length() && s.charAt(j) == ' ') j++;
                char nextC = j < s.length() ? s.charAt(j) : 0;

                // не создаём "--", "[[", "]]" там, где их не было
                if (prev >= 0 && j < s.length() && wouldCreateForbiddenPair(prevC, nextC)) {
                    if (prevC != ' ') {
                        out.append(' ');
                    }
                    i = j - 1;
                    continue;
                }

                // пропускаем пробел, если до него tight-символ
                if (prev >= 0 && isTightChar(prevC)) {
                    i = j - 1;
                    continue;
                }
                // пропускаем пробел, если после него tight-символ
                if (j < s.length() && isTightChar(nextC)) {
                    i = j - 1; // следующий шаг обработает tight-символ, пробелы съедены
                    continue;
                }
                // схлопнутый одиночный пробел между токенами
                if (prev >= 0 && prevC != ' ') {
                    out.append(' ');
                }
                i = j - 1;
                continue;
            }
            out.append(c);
        }
        // финальный trim возможных хвостовых пробелов
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        out.setLength(end);
        return out.toString();
    }
}
