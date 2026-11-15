package com.example.wowmcp.db;

import com.example.wowmcp.config.WowProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Подключение к PostgreSQL: пул, схема и флаг «БД доступна». Репозитории берут отсюда
 * {@link JdbcTemplate} и сообщают об ошибках записи. Если БД не настроена или упала,
 * сервер продолжает работать без сохранения.
 */
@Component
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** Сколько ошибок вставки подряд считаем признаком потери связи с БД. */
    private static final int MAX_FAILURES_IN_A_ROW = 5;

    private final WowProperties properties;
    private volatile boolean enabled = false;
    /** Ошибки вставки подряд: одна битая строка не должна выключать БД целиком. */
    private int failuresInARow = 0;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate namedJdbc;

    public Database(WowProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String url = properties.dbUrl();
        if (url == null || url.isBlank()) {
            log.info("wow.db.url не задан — БД отключена, события не сохраняются");
            return;
        }
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(properties.dbUsername());
            cfg.setPassword(properties.dbPassword());
            cfg.setMaximumPoolSize(2);
            cfg.setConnectionTimeout(5000);
            dataSource = new HikariDataSource(cfg);
            dataSource.getConnection().close();
            jdbc = new JdbcTemplate(dataSource);
            namedJdbc = new NamedParameterJdbcTemplate(dataSource);
            SchemaInitializer.create(jdbc);
            enabled = true;
            log.info("Подключено к БД: {}", url);
        } catch (Exception e) {
            log.warn("БД недоступна ({}): {} — сервер продолжает работу без сохранения", url, e.getMessage());
            closeQuietly();
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    NamedParameterJdbcTemplate named() {
        return namedJdbc;
    }

    /** Выключает БД и закрывает пул (после ошибки, похожей на потерю связи). */
    void disable() {
        enabled = false;
        closeQuietly();
    }

    void insertSucceeded() {
        failuresInARow = 0;
    }

    /** WARN со стектрейсом; БД выключаем только если ошибки идут подряд (похоже, связь потеряна). */
    void insertFailed(String what, Exception e) {
        failuresInARow++;
        log.warn("Не удалось записать {} (ошибок подряд: {}): {}", what, failuresInARow, e.getMessage(), e);
        if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
            log.warn("БД отключается: {} ошибок вставки подряд", failuresInARow);
            disable();
        }
    }

    private void closeQuietly() {
        try {
            if (dataSource != null) dataSource.close();
        } catch (Exception ignored) {
        }
        dataSource = null;
        jdbc = null;
    }

    @PreDestroy
    public void destroy() {
        closeQuietly();
    }
}
