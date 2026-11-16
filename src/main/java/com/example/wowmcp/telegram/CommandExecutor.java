package com.example.wowmcp.telegram;

import com.example.wowmcp.telegram.CommandRouter.Command;
import com.example.wowmcp.game.GameBridge;
import com.example.wowmcp.tools.BotActionTools;
import com.example.wowmcp.tools.GrindTools;
import com.example.wowmcp.tools.HuntTools;
import com.example.wowmcp.tools.InventoryTools;
import com.example.wowmcp.tools.PlaceTools;
import com.example.wowmcp.tools.RecordingTools;
import com.example.wowmcp.tools.RoadTools;
import com.example.wowmcp.tools.RotationTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Выполняет команды из Telegram теми же инструментами, что и MCP (без модели и токенов): каждая команда —
 * прямой вызов метода инструмента. Возвращает текст ответа; ошибки ввода и игры превращаются в понятное сообщение.
 */
@Component
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    static final String HELP = """
            Команды (можно и по-русски, как в OpenCode):
            статус · стоп · жди
            качайся [без лута] [по точкам] [N убийств] [M минут] · иди качайся
            продай · воскресни · призови питомца · отдохни · лут · бей
            сумки · места · маршруты · умения · ротация
            иди к ИМЯ · иди по маршруту ИМЯ · запомни место ИМЯ · запомни торговца ИМЯ
            иди X Y · беги X Y · построй дороги · перезагрузи бота
            /id — показать ваш chat id""";

    private final GameBridge bridge;
    private final BotActionTools actions;
    private final GrindTools grind;
    private final HuntTools hunt;
    private final InventoryTools inventory;
    private final PlaceTools places;
    private final RecordingTools recording;
    private final RotationTools rotation;
    private final RoadTools roads;

    public CommandExecutor(GameBridge bridge, BotActionTools actions, GrindTools grind, HuntTools hunt,
                           InventoryTools inventory, PlaceTools places, RecordingTools recording,
                           RotationTools rotation, RoadTools roads) {
        this.bridge = bridge;
        this.actions = actions;
        this.grind = grind;
        this.hunt = hunt;
        this.inventory = inventory;
        this.places = places;
        this.recording = recording;
        this.rotation = rotation;
        this.roads = roads;
    }

    /** Выполнить команду и вернуть текст ответа. Блокирует поток до ответа игры — вызывать не из потока опроса. */
    public String execute(Command c) {
        try {
            return switch (c.name()) {
                case "help" -> HELP;
                case "status" -> MessageFormatter.status(bridge.report());
                case "stop" -> MessageFormatter.toolResult(actions.stop());
                case "wait" -> MessageFormatter.status(actions.waitJob(25));
                case "grind" -> MessageFormatter.toolResult(grind.grind(
                        3.0, c.kills() == null ? 0 : c.kills(), c.minutes() == null ? 0 : c.minutes(),
                        null, null, null, null, null, null, c.flag(), null, null, c.arg(), null, null));
                case "hunt" -> MessageFormatter.toolResult(hunt.hunt(null, null, null));
                case "sell" -> MessageFormatter.toolResult(inventory.sellNow());
                case "revive" -> MessageFormatter.toolResult(actions.revive());
                case "pet" -> MessageFormatter.toolResult(actions.callPet());
                case "rest" -> MessageFormatter.toolResult(actions.rest());
                case "loot" -> MessageFormatter.toolResult(actions.loot());
                case "fight" -> MessageFormatter.toolResult(actions.fight());
                case "bags" -> MessageFormatter.toolResult(inventory.bags());
                case "places" -> MessageFormatter.toolResult(places.places());
                case "routes" -> MessageFormatter.toolResult(places.routes());
                case "go_place" -> MessageFormatter.toolResult(places.goPlace(c.arg()));
                case "go_route" -> MessageFormatter.toolResult(places.goRoute(c.arg(), null));
                case "remember_place" -> MessageFormatter.toolResult(places.rememberPlace(c.arg(), null));
                case "remember_vendor" -> MessageFormatter.toolResult(inventory.rememberVendor(c.arg()));
                case "remember_vendor_here" -> "Укажите имя торговца: «запомни торговца ИМЯ» (встаньте к нему вплотную).";
                case "record_start" -> MessageFormatter.toolResult(recording.recordStart());
                case "record_stop" -> MessageFormatter.toolResult(recording.recordStop(c.arg()));
                case "goto" -> goTo(c);
                case "roads" -> MessageFormatter.toolResult(roads.buildRoads());
                case "rotation" -> MessageFormatter.toolResult(rotation.getRotation());
                case "learn" -> MessageFormatter.toolResult(rotation.learnRotation());
                case "spells" -> MessageFormatter.toolResult(rotation.spells());
                case "profile" -> MessageFormatter.toolResult(rotation.setProfile(c.arg()));
                case "reload" -> MessageFormatter.toolResult(actions.reloadBot());
                default -> "Не понял команду. Напишите «помощь».";
            };
        } catch (IllegalArgumentException e) {
            return "Ошибка в команде: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("Команда {} не выполнена: {}", c.name(), e.getMessage());
            return "Не получилось: " + e.getMessage();
        }
    }

    private String goTo(Command c) {
        String[] xy = c.arg().split(" ");
        double x = Double.parseDouble(xy[0]), y = Double.parseDouble(xy[1]);
        return MessageFormatter.toolResult(c.flag() ? actions.goTo(x, y, true, true) : actions.moveTo(x, y, true));
    }
}
