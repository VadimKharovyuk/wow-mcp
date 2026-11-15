package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** Профили: выученная ротация (rotation_profile) и активный профиль (active_profile). */
@Component
public class ProfileRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepository.class);

    public ProfileRepository(Database db) {
        super(db);
    }

    public void upsertRotationProfile(String profile, String rotationJson, int samples, String statsJson) {
        if (!isEnabled()) return;
        try {
            jdbc().update("""
                    INSERT INTO rotation_profile (profile, rotation_json, samples, learned_at, last_used_at, stats_json)
                    VALUES (?,?,?,?,now(),?)
                    ON CONFLICT (profile) DO UPDATE SET
                      rotation_json = EXCLUDED.rotation_json,
                      samples = EXCLUDED.samples,
                      learned_at = EXCLUDED.learned_at,
                      last_used_at = now(),
                      stats_json = EXCLUDED.stats_json""",
                    profile, rotationJson, samples, new Timestamp(System.currentTimeMillis()), statsJson);
        } catch (Exception e) {
            log.warn("Не удалось сохранить профиль ротации: {}", e.getMessage());
            db.disable();
        }
    }

    /** Запоминает активный профиль в БД, чтобы после перезапуска сервера его нашёл autoLoadProfile. */
    public void setActiveProfile(String profile) {
        if (!isEnabled()) return;
        try {
            if (profile == null || profile.isBlank()) {
                jdbc().update("DELETE FROM active_profile WHERE id = 1");
                return;
            }
            jdbc().update("""
                    INSERT INTO active_profile (id, profile, updated_at) VALUES (1, ?, now())
                    ON CONFLICT (id) DO UPDATE SET profile = EXCLUDED.profile, updated_at = now()""",
                    profile);
        } catch (Exception e) {
            log.warn("Не удалось сохранить активный профиль: {}", e.getMessage(), e);
        }
    }

    /** Активный профиль из БД (null, если не задан). */
    public String findActiveProfile() {
        if (!isEnabled()) return null;
        try {
            List<Map<String, Object>> rows = jdbc().queryForList("SELECT profile FROM active_profile WHERE id = 1");
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("profile"));
        } catch (Exception e) {
            log.warn("Не удалось прочитать активный профиль: {}", e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> loadRotationProfile(String profile) {
        if (!isEnabled() || profile == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc().queryForList(
                    "SELECT rotation_json, samples, stats_json, last_used_at FROM rotation_profile WHERE profile = ?", profile);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("Не удалось загрузить профиль ротации: {}", e.getMessage());
            return null;
        }
    }

    public void touchProfile(String profile) {
        if (!isEnabled() || profile == null) return;
        try {
            jdbc().update("UPDATE rotation_profile SET last_used_at = now() WHERE profile = ?", profile);
        } catch (Exception e) {
            log.warn("Не удалось обновить last_used_at: {}", e.getMessage());
        }
    }

    public void deleteProfileData(String profile) {
        if (!isEnabled() || profile == null) return;
        try {
            jdbc().update("DELETE FROM key_press WHERE profile = ?", profile);
            jdbc().update("DELETE FROM rotation_profile WHERE profile = ?", profile);
            jdbc().update("DELETE FROM active_profile WHERE profile = ?", profile);
        } catch (Exception e) {
            log.warn("Не удалось удалить данные профиля: {}", e.getMessage());
            db.disable();
        }
    }

    public String findLastUsedProfile() {
        if (!isEnabled()) return null;
        try {
            List<Map<String, Object>> rows = jdbc().queryForList(
                    "SELECT profile FROM rotation_profile ORDER BY last_used_at DESC LIMIT 1");
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("profile"));
        } catch (Exception e) {
            log.warn("Не удалось найти последний профиль: {}", e.getMessage());
            return null;
        }
    }
}
