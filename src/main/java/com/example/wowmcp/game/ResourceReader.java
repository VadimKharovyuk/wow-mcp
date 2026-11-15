package com.example.wowmcp.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Чтение текстовых ресурсов из classpath и с диска (UTF-8). */
final class ResourceReader {

    private ResourceReader() {
    }

    static String readResource(String name) {
        try (InputStream in = ResourceReader.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Не найден ресурс " + name + " в classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать ресурс " + name, e);
        }
    }

    static List<String> readResourceLines(String name) {
        return readResource(name).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + path, e);
        }
    }

    /** Имена модулей из manifest.txt: по одному на строку, пустые строки и «#» пропускаются. */
    static List<String> parseManifest(String manifest) {
        return manifest.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /** Имя модуля не должно выходить из каталога. */
    static void checkModuleName(String module, String manifestName) {
        if (module.contains("..") || module.contains("/") || module.contains("\\")) {
            throw new IllegalStateException("Недопустимое имя модуля в " + manifestName + ": " + module);
        }
    }
}
