package com.example.wowmcp.telegram;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

/**
 * Режим «контроллер» ({@code -Dwow.controller=true -Dspring.ai.mcp.server.enabled=false}, см. controller.sh): процесс
 * без MCP-сервера. Остальные потоки приложения — демоны, поэтому без этого потока JVM завершилась бы сразу после
 * старта. В обычном режиме (MCP из OpenCode) компонент ничего не делает: процесс живёт, пока открыт stdio.
 */
@Component
public class ControllerKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(ControllerKeepAlive.class);

    private final boolean controller;
    private final CountDownLatch stop = new CountDownLatch(1);

    public ControllerKeepAlive(@Value("${wow.controller:false}") boolean controller) {
        this.controller = controller;
    }

    @PostConstruct
    void start() {
        if (!controller) return;
        Thread t = new Thread(() -> {
            try {
                stop.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "wow-controller-keepalive");
        t.setDaemon(false);
        t.start();
        log.info("Режим контроллера: процесс остаётся запущенным до Ctrl+C");
        // В режиме контроллера stdout свободен (протокола MCP нет): коротко говорим, что происходит. Логи — в файле.
        System.out.println("Контроллер wow-mcp запущен. Логи: logs/wow-mcp.log. Остановка: Ctrl+C.");
        System.out.println("Telegram: напишите вашему боту /id, впишите chat id в telegram.local.properties и перезапустите.");
    }

    @PreDestroy
    void stop() {
        stop.countDown();
    }
}
