package com.example.wowmcp.game;

import com.example.wowmcp.config.WowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.example.wowmcp.game.ResourceReader.checkModuleName;
import static com.example.wowmcp.game.ResourceReader.parseManifest;
import static com.example.wowmcp.game.ResourceReader.readFile;
import static com.example.wowmcp.game.ResourceReader.readResource;
import static com.example.wowmcp.game.ResourceReader.readResourceLines;

/**
 * Источники Lua-подготовки: {@code lua-setup.txt} (однострочники) и {@code lua/*.lua}
 * (многострочные модули по {@code lua/manifest.txt}). Из них собираются строки для чата
 * и версия, по которой бот узнаёт, что Lua в игре устарел.
 *
 * <p>Если задано {@code wow.lua-dir}, модули читаются с диска (режим разработки).
 */
@Component
class LuaSources {

    private static final Logger log = LoggerFactory.getLogger(LuaSources.class);

    /** Результат подготовки Lua-модулей: очищенный/сжатый код и статистика. */
    record Prepared(int files, int originalBytes, int compressedBytes, List<String> chunks) {}

    private final WowProperties properties;

    LuaSources(WowProperties properties) {
        this.properties = properties;
    }

    /** Собирает строки Lua-подготовки: старые однострочники + новые многострочные lua/*.lua (чанками) + версия. */
    List<String> setupLines() {
        List<String> lines = new ArrayList<>(readResourceLines("lua-setup.txt"));
        Prepared modules = prepareModules(properties.luaDir());
        if (!modules.chunks().isEmpty()) {
            log.info("Lua-модули: {} файлов, {} кусков (до сжатия {} байт, после {} байт)",
                    modules.files(), modules.chunks().size(), modules.originalBytes(), modules.compressedBytes());
            for (String chunk : modules.chunks()) {
                lines.add("/run _L=(_L or \"\")..[==[" + chunk + "]==]");
            }
            lines.add("/run local f,e=loadstring(_L) _L=nil if f then f() else print(\"LUA ERR\",e) end");
        }
        lines.add("/run B[30]=" + version());
        return List.copyOf(lines);
    }

    /** Хеш всех Lua-источников (lua-setup.txt + lua/*.lua). */
    int version() {
        StringBuilder sb = new StringBuilder(readResource("lua-setup.txt"));
        sb.append(readModulesCombined(properties.luaDir()));
        return sb.toString().hashCode() & 0xFFFFF;
    }

    /** Читает, сжимает и нарезает Lua-модули. */
    private Prepared prepareModules(String dir) {
        List<String> modules = readModuleNames(dir);
        String combined = readModulesCombined(dir);
        if (combined.isEmpty()) {
            return new Prepared(modules.size(), 0, 0, List.of());
        }
        if (combined.indexOf('|') >= 0) {
            throw new IllegalStateException("В Lua-исходниках найден символ '|': он экранируется чатом WoW, используй 'or' или string.char(124)");
        }
        int originalBytes = combined.getBytes(StandardCharsets.UTF_8).length;
        String compressed = LuaMinifier.compressLua(combined);
        if (compressed.isEmpty()) {
            return new Prepared(modules.size(), originalBytes, 0, List.of());
        }
        List<String> chunks = LuaMinifier.chunk(compressed);
        int compressedBytes = compressed.getBytes(StandardCharsets.UTF_8).length;
        return new Prepared(modules.size(), originalBytes, compressedBytes, chunks);
    }

    /** Конкатенирует содержимое lua-модулей (classpath или диск). */
    private String readModulesCombined(String dir) {
        StringBuilder sb = new StringBuilder();
        for (String module : readModuleNames(dir)) {
            checkModuleName(module, "lua/manifest.txt");
            String source = (dir == null)
                    ? readResource("lua/" + module)
                    : readFile(Path.of(dir, module));
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(source);
        }
        return sb.toString();
    }

    /** Список имён lua-модулей из manifest.txt (classpath или диск). Если манифеста нет — пустой список. */
    private List<String> readModuleNames(String dir) {
        String manifest;
        try {
            manifest = (dir == null)
                    ? readResource("lua/manifest.txt")
                    : readFile(Path.of(dir, "manifest.txt"));
        } catch (RuntimeException e) {
            return List.of();
        }
        return parseManifest(manifest);
    }
}
