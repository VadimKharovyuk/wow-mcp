package com.example.wowmcp.game;

import com.example.wowmcp.config.WowProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

import static com.example.wowmcp.game.ResourceReader.checkModuleName;
import static com.example.wowmcp.game.ResourceReader.parseManifest;
import static com.example.wowmcp.game.ResourceReader.readFile;
import static com.example.wowmcp.game.ResourceReader.readResource;

/**
 * Собирает JS бота из модулей {@code bot/*.js} в порядке {@code bot/manifest.txt}.
 * Если задано {@code wow.bot-js-dir}, модули читаются с диска при каждой загрузке
 * (пересборка не нужна), иначе — из classpath.
 */
@Component
class BotScriptLoader {

    private static final String BOT_MANIFEST_RESOURCE = "bot/manifest.txt";
    private static final String BOT_MODULE_PREFIX = "bot/";
    private static final String BOT_MODULE_JOINER = ";\n";

    private final WowProperties properties;

    BotScriptLoader(WowProperties properties) {
        this.properties = properties;
    }

    /** Читает модули по порядку из manifest.txt и склеивает их через ";\n". */
    String read() {
        String dir = properties.botJsDir();
        List<String> modules = readModules(dir);

        StringBuilder js = new StringBuilder();
        for (String module : modules) {
            String source = readModule(dir, module).stripTrailing();
            if (source.isEmpty()) {
                continue;
            }
            if (!js.isEmpty()) {
                js.append(BOT_MODULE_JOINER);
            }
            js.append(source);
        }
        return js.toString();
    }

    /** Откуда берётся bot.js — для логов и отчёта reload_bot. */
    String describeSource() {
        String dir = properties.botJsDir();
        return dir == null ? "из classpath (bot/manifest.txt)" : "с диска " + dir;
    }

    /** Список модулей из manifest.txt: по одному имени на строку, # — комментарий. */
    private static List<String> readModules(String dir) {
        String manifestPath = dir == null ? BOT_MANIFEST_RESOURCE : dir + "/manifest.txt";
        String manifest = dir == null
                ? readResource(BOT_MANIFEST_RESOURCE)
                : readFile(Path.of(dir, "manifest.txt"));

        List<String> modules = parseManifest(manifest);
        if (modules.isEmpty()) {
            throw new IllegalStateException("Манифест модулей бота пуст: " + manifestPath);
        }
        return modules;
    }

    private static String readModule(String dir, String module) {
        checkModuleName(module, "manifest.txt");
        return dir == null
                ? readResource(BOT_MODULE_PREFIX + module)
                : readFile(Path.of(dir, module));
    }
}
