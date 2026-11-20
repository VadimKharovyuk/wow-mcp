package com.example.wowmcp.db;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * «Лидер» среди процессов wow-mcp, подключённых к одной БД (MCP-сервер из OpenCode и отдельный контроллер).
 * Лидер единственный читает события из игры в БД (EventCollector) ; остальные ждут.
 * Реализация — сессионная advisory-блокировка PostgreSQL на выделенном соединении: умер процесс — блокировка
 * освобождается сама, и лидером становится другой процесс. Без БД процесс считается лидером.
 */
@Component
public class LeaderLock {

    private static final Logger log = LoggerFactory.getLogger(LeaderLock.class);

    /** Ключ блокировки (ASCII «WOWMCP»). */
    static final long KEY = 0x574F574D4350L;

    private final Database db;
    private Connection conn;
    private volatile boolean leader;

    public LeaderLock(Database db) {
        this.db = db;
    }

    /** Этот процесс — лидер. Без БД всегда true. */
    public boolean isLeader() {
        return !db.isEnabled() || leader;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 500)
    public void tick() {
        if (!db.isEnabled()) {
            return;
        }
        try {
            if (conn == null || conn.isClosed() || !conn.isValid(2)) {
                if (leader) log.warn("Соединение лидера потеряно — лидерство отпущено");
                dropConnection();
                leader = false;
                conn = db.newConnection();
            }
            if (!leader) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                    ps.setLong(1, KEY);
                    try (ResultSet rs = ps.executeQuery()) {
                        leader = rs.next() && rs.getBoolean(1);
                    }
                }
                if (leader) log.info("Этот процесс стал лидером: события игры обрабатывает он");
            }
        } catch (Exception e) {
            log.warn("Проверка лидерства не удалась: {}", e.getMessage());
            dropConnection();
            leader = false;
        }
    }

    private void dropConnection() {
        try {
            if (conn != null) {
                if (leader && !conn.isClosed()) {
                    // соединение вернётся в пул живым — блокировку нужно снять явно
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, KEY);
                        ps.execute();
                    }
                }
                conn.close();
            }
        } catch (Exception ignored) {
        }
        conn = null;
    }

    @PreDestroy
    public void release() {
        leader = false;
        dropConnection();
    }
}
