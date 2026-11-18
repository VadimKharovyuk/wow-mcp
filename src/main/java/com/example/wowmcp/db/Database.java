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
            cfg.setMaximumPoolSize(4);   // одно соединение постоянно держит LeaderLock
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

    /**
     * Реакция на ошибку записи/чтения: выключаем БД только если пропала связь (нет соединения, SQLState 08xxx).
     * Ошибка данных (значение не влезло, нарушение ограничения и т.п.) БД не выключает — пропадает только одна запись.
     */
    void failed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException || t instanceof java.net.ConnectException
                    || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                    || (t instanceof java.sql.SQLException se && se.getSQLState() != null && se.getSQLState().startsWith("08"))) {
                log.warn("БД отключается: потеряна связь ({})", t.getMessage());
                disable();
                return;
            }
        }
    }

    /** Выключает БД и закрывает пул (после ошибки, похожей на потерю связи). */
    void disable() {
        enabled = false;
        closeQuietly();
    }

    /** Отдельное соединение для долгоживущих сессионных блокировок (закрывать должен вызывающий). */
    java.sql.Connection newConnection() throws java.sql.SQLException {
        return dataSource.getConnection();
    }

    /** Выполнить действие в одной транзакции (иначе при сбое половина записей останется). */
    void inTransaction(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource)).executeWithoutResult(s -> action.run());
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
