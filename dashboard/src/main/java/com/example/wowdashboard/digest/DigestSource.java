package com.example.wowdashboard.digest;

import java.util.Map;

/** Источник сводки журнала за период: потребители (разбор моделью) зависят от этого интерфейса, а не от SQL. */
public interface DigestSource {

    /** Сводка за последние {@code minutes} минут (структура описана в {@link DigestService}). */
    Map<String, Object> digest(int minutes);
}
