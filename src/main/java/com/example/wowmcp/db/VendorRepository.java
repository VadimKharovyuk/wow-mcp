package com.example.wowmcp.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Торговцы (vendor). */
@Component
public class VendorRepository extends BaseRepository {

    private static final Logger log = LoggerFactory.getLogger(VendorRepository.class);

    public VendorRepository(Database db) {
        super(db);
    }

    public void rememberVendor(int zone, double x, double y, String name, Double facing) {
        if (!isEnabled() || name == null || name.isBlank()) return;
        try {
            jdbc().update("DELETE FROM vendor WHERE zone = ? AND name = ?", zone, name);
            jdbc().update("INSERT INTO vendor (zone,x,y,name,facing) VALUES (?,?,?,?,?)", zone, x, y, name, facing);
        } catch (Exception e) {
            log.warn("Не удалось сохранить торговца: {}", e.getMessage());
            db.disable();
        }
    }

    public List<Map<String, Object>> listVendors(int zone) {
        if (!isEnabled()) return List.of();
        try {
            return jdbc().query("""
                    SELECT id, zone, x, y, name, facing, created_at
                    FROM vendor WHERE zone = ? ORDER BY created_at DESC""",
                    (rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getInt("id"));
                        m.put("zone", rs.getInt("zone"));
                        m.put("x", (double) rs.getFloat("x"));
                        m.put("y", (double) rs.getFloat("y"));
                        float facing = rs.getFloat("facing");
                        m.put("facing", rs.wasNull() ? null : (double) facing);
                        m.put("name", rs.getString("name"));
                        m.put("created_at", rs.getTimestamp("created_at"));
                        return m;
                    }, zone);
        } catch (Exception e) {
            log.warn("Не удалось прочитать торговцев: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> nearestVendor(int zone, double x, double y) {
        List<Map<String, Object>> list = listVendors(zone);
        Map<String, Object> best = null;
        double bestD = Double.MAX_VALUE;
        for (Map<String, Object> v : list) {
            double vx = ((Number) v.get("x")).doubleValue();
            double vy = ((Number) v.get("y")).doubleValue();
            double d = Math.hypot(vx - x, vy - y);
            if (d < bestD) { bestD = d; best = v; }
        }
        return best;
    }
}
