package com.example.wowmcp.game;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.KeepItemRepository;
import com.example.wowmcp.db.ProfileRepository;
import com.example.wowmcp.learn.RotationLearner;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Переносит сохранённое в БД состояние в только что подготовленную игру:
 * активный профиль с ротацией и список предметов, которые нельзя продавать (KEEP).
 *
 * <p>Методы вызываются только из потока Playwright (внутри {@link PageConnection#call}).
 */
@Component
class GameSessionSync {

    private static final Logger log = LoggerFactory.getLogger(GameSessionSync.class);

    /** Максимум байт в одной {@code /run}-строке чата с KEEP. */
    private static final int KEEP_CMD_MAX_BYTES = 200;

    private final PageConnection connection;
    private final Database db;
    private final KeepItemRepository keepItems;
    private final ProfileRepository profiles;
    private final RotationLearner learner;

    GameSessionSync(PageConnection connection, Database db, KeepItemRepository keepItems, ProfileRepository profiles, RotationLearner learner) {
        this.connection = connection;
        this.db = db;
        this.keepItems = keepItems;
        this.profiles = profiles;
        this.learner = learner;
    }

    /** Отправляет KEEP[id]=true в игру батчами (каждая строка <= 200 байт), без /run на каждый предмет. */
    void syncKeepItems() {
        if (db == null || !db.isEnabled()) return;
        List<Integer> ids;
        try {
            ids = keepItems.listIds();
        } catch (RuntimeException e) {
            log.warn("Не удалось прочитать keep_item для синхронизации: {}", e.getMessage());
            return;
        }
        if (ids == null || ids.isEmpty()) return;
        Page page = connection.page();
        StringBuilder batch = new StringBuilder();
        for (int id : ids) {
            String piece = (batch.length() == 0) ? String.valueOf(id) : "," + id;
            String cmd = "/run for _,i in ipairs({" + batch + piece + "}) do KEEP[i]=true end";
            if (cmd.getBytes(StandardCharsets.UTF_8).length > KEEP_CMD_MAX_BYTES) {
                if (batch.length() > 0) {
                    sendKeepBatch(page, batch);
                }
                batch = new StringBuilder(String.valueOf(id));
            } else {
                if (batch.length() > 0) batch.append(',');
                else batch = new StringBuilder();
                batch.append(id);
            }
        }
        if (batch.length() > 0) {
            sendKeepBatch(page, batch);
        }
        log.info("Синхронизировано KEEP предметов в игру: {}", ids.size());
    }

    private static void sendKeepBatch(Page page, StringBuilder batch) {
        page.evaluate("t => chat(t)", "/run for _,i in ipairs({" + batch + "}) do KEEP[i]=true end");
        PageConnection.sleep(PageConnection.CHAT_LINE_PAUSE_MS);
    }

    /** Автозагрузка последнего профиля после загрузки нового window.bot. */
    void autoLoadProfile() {
        if (db == null || !db.isEnabled() || learner == null) return;
        // Сначала явно сохранённый активный профиль (set_profile), иначе — последний использованный.
        String profile = profiles.findActiveProfile();
        if (profile == null || profile.isBlank()) {
            profile = profiles.findLastUsedProfile();
        }
        if (profile == null || profile.isBlank()) return;
        Page page = connection.page();
        try {
            page.evaluate("(p) => bot.setProfile ? bot.setProfile(p) : (bot.profile = p, window.__wowProfile = p)", profile);
            Map<String, Object> row = profiles.loadRotationProfile(profile);
            if (row != null && row.get("rotation_json") != null) {
                tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
                Object rotation = om.readValue(String.valueOf(row.get("rotation_json")), Object.class);
                page.evaluate("(r) => { bot.rotation = r; return 'ok'; }", rotation);
            }
            profiles.touchProfile(profile);
            log.info("Автозагрузка профиля '{}': ротация {}", profile,
                    row == null ? "по умолчанию" : row.get("rotation_json"));
        } catch (Exception e) {
            log.warn("Автозагрузка профиля не удалась: {}", e.getMessage());
        }
    }
}
