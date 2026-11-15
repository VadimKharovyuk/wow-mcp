package com.example.wowmcp.tools;

import com.example.wowmcp.db.Database;
import com.example.wowmcp.db.HuntRepository;
import com.example.wowmcp.db.KillRepository;
import com.example.wowmcp.db.VendorRepository;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.game.GameState;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.wowmcp.tools.GrindOptions.DEFAULT_MAX_LVL_DIFF;
import static com.example.wowmcp.tools.GrindOptions.DEFAULT_MIN_LVL_DIFF;
import static com.example.wowmcp.tools.ToolSupport.JSON;
import static com.example.wowmcp.tools.ToolSupport.vendorArg;

/** MCP-инструменты авто-охоты: {@code grind} и просмотр известных мест охоты. */
@Component
public class GrindTools {

    private final GameBridge bridge;
    private final Database db;
    private final VendorRepository vendors;
    private final KillRepository kills;
    private final HuntRepository hunts;

    public GrindTools(GameBridge bridge, Database db, VendorRepository vendors, KillRepository kills,
                      HuntRepository hunts) {
        this.bridge = bridge;
        this.db = db;
        this.vendors = vendors;
        this.kills = kills;
        this.hunts = hunts;
    }

    @Tool(name = "grind", description = """
            Автоматическая охота вокруг текущей точки: найти подходящего моба, подойти, \
            убить, собрать добычу (loot), отдохнуть, повторить. Работает в фоне. Возвращается по \
            лимиту убийств/времени или при проблеме (смерть, застрял, сумки полные, мобов нет). \
            Во время охоты вызывай wait_job; статистика — в поле grind. \
            loot — собирать добычу после боя (true/false, по умолчанию false).

            Если в базе есть места прошедших убийств в этой зоне — бот ходит по ним (быстрее), \
            иначе ищет вокруг. Параметр radius ограничивает зону охоты вокруг точки старта. \
            respawnSec — через сколько секунд после визита место снова считается «тёплым».
            mode — режим обхода мест: 'radius' (по умолчанию, охота вокруг старта) или
            'patrol' (кольцевой обход мест зоны в пределах maxDistance, 2..40, по умолчанию 15).
            minFreeSlots — при таком/меньшем числе свободных слотов идти к ближайшему торговцу зоны
            (работает при любом loot); если торговец не известен — гринд остановится.
            autoRevive — после смерти отпустить дух, добежать до трупа, воскреснуть и продолжить (по умолчанию true);
            3 смерти за 10 минут останавливают гринд (статус stopped).""")
    public String grind(
            @ToolParam(required = false, description = "Радиус охоты вокруг старта в ЕДИНИЦАХ КАРТЫ (не метры), 0.5..10 (по умолчанию 2.5); 1 ед. ≈ 50 м в небольшой зоне")
            Double radius,
            @ToolParam(required = false, description = "Лимит убийств, 0 = без лимита (по умолчанию 10)")
            Integer maxKills,
            @ToolParam(required = false, description = "Лимит времени в минутах, 1..180 (по умолчанию 30)")
            Integer maxMinutes,
            @ToolParam(required = false, description = "Минимальная разница уровней цели, -10..5 (по умолчанию -5)")
            Integer minLvlDiff,
            @ToolParam(required = false, description = "Максимальная разница уровней цели, -10..5 (по умолчанию 2)")
            Integer maxLvlDiff,
            @ToolParam(required = false, description = "Порог маны для питья, 0..1 (по умолчанию 0.3)")
            Double manaBelow,
            @ToolParam(required = false, description = "Слот лечения в отдыхе, '0'..'9' (по умолчанию '4')")
            String healKey,
            @ToolParam(required = false, description = "Слот питья в отдыхе, '0'..'9' (по умолчанию '0')")
            String drinkKey,
            @ToolParam(required = false, description = "Использовать базу тёплых точек охоты, true/false (по умолчанию true)")
            Boolean useSpots,
            @ToolParam(required = false, description = "Собирать добычу после боя, true/false (по умолчанию false)")
            Boolean loot,
            @ToolParam(required = false, description = "Через сколько секунд место снова считается тёплым, 60..1800 (по умолчанию 240)")
            Integer respawnSec,
            @ToolParam(required = false, description = "При таком/меньшем числе свободных слотов идти к торговцу, 0..10 (по умолчанию 2)")
            Integer minFreeSlots,
            @ToolParam(required = false, description = "Режим обхода мест: 'radius' (по умолчанию) или 'patrol'")
            String mode,
            @ToolParam(required = false, description = "Максимальное расстояние до мест маршрута в patrol, 2..40 (по умолчанию 15)")
            Double maxDistance,
            @ToolParam(required = false, description = "После смерти воскреснуть у трупа и продолжить, true/false (по умолчанию true); "
                    + "3 смерти за 10 минут останавливают гринд")
            Boolean autoRevive) {
        Map<String, Object> opts = GrindOptions.validated(radius, maxKills, maxMinutes, minLvlDiff, maxLvlDiff,
                manaBelow, healKey, drinkKey, loot, respawnSec, minFreeSlots, mode, maxDistance, autoRevive);
        boolean patrol = "patrol".equals(opts.get("mode"));

        // Тёплые точки охоты из БД (задача 4б): только если БД доступна и useSpots не false.
        // В patrol берём все места зоны без фильтра по радиусу (фильтр по уровню моба — тот же),
        // маршрут и ограничение maxDistance строит 40-grind.js.
        List<Map<String, Object>> spots = List.of();
        Map<String, Object> vendorOpt = null;
        if (db.isEnabled()) {
            GameState state = GameState.parse(bridge.report());
            // vendor for this zone (nearest) — передаём всегда при наличии в БД
            Map<String, Object> v = vendors.nearestVendor(state.zone(), state.x(), state.y());
            if (v != null) {
                vendorOpt = vendorArg(v);
                opts.put("vendor", vendorOpt);
            }
            // имена мобов для /target имя: Tab не видит нейтральных (жёлтых) мобов
            Integer zoneId = hunts.zoneId(state.zone());
            if (zoneId != null) {
                double r = patrol ? (maxDistance == null ? 15.0 : maxDistance) : (radius == null ? 2.5 : radius);
                List<String> names = hunts.namesAround(zoneId, state.pLvl(), state.x(), state.y(), r);
                if (!names.isEmpty()) {
                    opts.put("names", names);
                }
            }
            if (useSpots == null || useSpots || patrol) {
                int minLvl = state.pLvl() + (minLvlDiff == null ? DEFAULT_MIN_LVL_DIFF : minLvlDiff);
                int maxLvl = state.pLvl() + (maxLvlDiff == null ? DEFAULT_MAX_LVL_DIFF : maxLvlDiff);
                spots = kills.querySpots(state.zone(), minLvl, maxLvl);
                if (!spots.isEmpty()) {
                    opts.put("spots", GrindOptions.cleanSpots(spots));
                }
            }
        }
        List<Object> args = opts.isEmpty() ? List.of() : List.of(opts);
        String startResult = bridge.start("grind", args);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", startResult);
        response.put("mode", patrol ? "patrol" : "radius");
        response.put("spots", spots.size());
        response.put("vendor", vendorOpt != null);
        return JSON.writeValueAsString(response);
    }

    @Tool(name = "spots", description = """
            Что бот знает о текущей зоне: убийства сгруппированы по сетке 1×1 единица карты \
            (центр, количество, средний уровень моба, последнее убийство). Только места с >= 2 \
            убийствами за 30 дней. Топ-30 по количеству. Без БД — сообщение «база не настроена».""")
    public String spots(
            @ToolParam(required = false, description = "Минимальная разница уровней цели, -10..5 (по умолчанию -5)")
            Integer minLvlDiff,
            @ToolParam(required = false, description = "Максимальная разница уровней цели, -10..5 (по умолчанию 2)")
            Integer maxLvlDiff) {
        bridge.ensureReady();
        GameState state = GameState.parse(bridge.report());
        int zone = state.zone();
        if (!db.isEnabled()) {
            return JSON.writeValueAsString(Map.of("result", "база не настроена", "zone", zone));
        }
        int diffMin = minLvlDiff == null ? DEFAULT_MIN_LVL_DIFF : minLvlDiff;
        int diffMax = maxLvlDiff == null ? DEFAULT_MAX_LVL_DIFF : maxLvlDiff;
        List<Map<String, Object>> rows = kills.querySpots(zone, state.pLvl() + diffMin, state.pLvl() + diffMax);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", zone);
        result.put("spots", rows);
        return JSON.writeValueAsString(result);
    }
}
