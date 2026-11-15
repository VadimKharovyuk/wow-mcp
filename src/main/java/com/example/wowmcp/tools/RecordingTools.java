package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.KillRepository;
import com.example.wowmcp.db.PathRepository;
import com.example.wowmcp.game.GameBridge;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.example.wowmcp.tools.ToolSupport.JSON;

/** MCP-инструменты режима обучения: запись пути и убийств, пока играет человек. */
@Component
public class RecordingTools {

    private final GameBridge bridge;
    private final Database db;
    private final PathRepository paths;
    private final KillRepository kills;

    private volatile String lastRecordSession;
    private volatile Instant lastRecordStart;

    public RecordingTools(GameBridge bridge, Database db, PathRepository paths, KillRepository kills) {
        this.bridge = bridge;
        this.db = db;
        this.paths = paths;
        this.kills = kills;
    }

    @Tool(name = "record_start", description = """
            Включить режим обучения: бот пишет путь и убийства в БД, пока играет человек. \
            Возвращает sessionId для record_stop.""")
    public String recordStart() {
        bridge.ensureReady();
        String sessionId = UUID.randomUUID().toString();
        bridge.eval("(s) => { bot.recording = true; bot.recSession = s; return bot.recSession; }", sessionId);
        lastRecordSession = sessionId;
        lastRecordStart = Instant.now();
        return JSON.writeValueAsString(Map.of("result", "запись начата", "sessionId", sessionId));
    }

    @Tool(name = "record_stop", description = """
            Остановить запись. Возвращает сводку сессии: точек пути и убийств (из БД, если настроена).""")
    public String recordStop() {
        bridge.ensureReady();
        bridge.eval("() => { bot.recording = false; return 'ok'; }");
        String sid = lastRecordSession;
        long pathPoints = db.isEnabled() ? paths.countPathPoints(sid) : 0;
        long killCount = db.isEnabled() && lastRecordStart != null ? kills.countKillsSince(lastRecordStart) : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "запись остановлена");
        result.put("sessionId", sid);
        result.put("pathPoints", pathPoints);
        result.put("kills", killCount);
        result.put("dbEnabled", db.isEnabled());
        return JSON.writeValueAsString(result);
    }
}
