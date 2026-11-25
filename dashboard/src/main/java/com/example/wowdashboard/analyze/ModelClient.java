package com.example.wowdashboard.analyze;

/** Языковая модель, которой отправляется сводка. Реализация сейчас одна (Ollama); интерфейс нужен, чтобы подменять модель и тестировать без неё. */
interface ModelClient {

    /** Ответ модели на пару «инструкция + сводка». Исключения транспорта пробрасываются вызывающему. */
    String chat(String system, String user, String model);
}
