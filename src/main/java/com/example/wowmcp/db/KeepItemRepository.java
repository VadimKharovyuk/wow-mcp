package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Предметы, которые нельзя продавать (keep_item). */
@Component
public class KeepItemRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(KeepItemRepository.class);

    public KeepItemRepository(Database db) {
        super(db);
    }

    public void add(int itemId) {
        if (!isEnabled()) return;
        try {
            jdbc().update("INSERT INTO keep_item (item_id) VALUES (?) ON CONFLICT (item_id) DO NOTHING", itemId);
        } catch (Exception e) {
            // fallback for DBs without ON CONFLICT in some modes; try plain insert
            try {
                jdbc().update("INSERT INTO keep_item (item_id) VALUES (?)", itemId);
            } catch (Exception e2) {
                log.warn("Не удалось сохранить keep_item: {}", e2.getMessage());
                db.failed(e2);
            }
        }
    }

    public List<Integer> listIds() {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("SELECT item_id FROM keep_item ORDER BY item_id",
                    (rs, rowNum) -> rs.getInt("item_id"));
        } catch (Exception e) {
            log.warn("Не удалось прочитать keep_item: {}", e.getMessage());
            return List.of();
        }
    }
}
