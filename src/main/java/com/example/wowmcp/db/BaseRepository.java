package com.example.wowmcp.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Основа репозиториев: общий доступ к {@link Database}. Когда БД выключена, методы-читатели возвращают пустое. */
public abstract class BaseRepository {

    protected final Database db;

    protected BaseRepository(Database db) {
        this.db = db;
    }

    public boolean isEnabled() {
        return db.isEnabled();
    }

    protected JdbcTemplate jdbc() {
        return db.jdbc();
    }

    protected NamedParameterJdbcTemplate named() {
        return db.named();
    }
}
