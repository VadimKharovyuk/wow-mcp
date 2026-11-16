package com.example.wowmcp.telegram;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор текста из Telegram в команду: понимает {@code /команды} и те же русские фразы, что в AGENTS.md
 * («качайся», «иди к гарпиям», «запомни место ИМЯ»). Чистая функция, без обращения к игре.
 */
public final class CommandRouter {

    /** Команда: имя действия и аргумент (строка, число или null). */
    public record Command(String name, String arg, Integer kills, Integer minutes, boolean flag) {
        static Command of(String name) {
            return new Command(name, null, null, null, false);
        }

        static Command of(String name, String arg) {
            return new Command(name, arg, null, null, false);
        }
    }

    static final String UNKNOWN = "unknown";

    private static final Pattern COORDS = Pattern.compile("^(?:иди|беги)\\s+(\\d{1,3}(?:[.,]\\d+)?)\\s+(\\d{1,3}(?:[.,]\\d+)?)$");
    private static final Pattern KILLS = Pattern.compile("(\\d+)\\s*убий");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*мин");

    private CommandRouter() {
    }

    public static Command parse(String raw) {
        if (raw == null) return Command.of(UNKNOWN);
        String text = raw.strip();
        // /команда@имя_бота арг
        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String cmd = parts[0].split("@")[0].toLowerCase(Locale.ROOT);
            String arg = parts.length > 1 ? parts[1].strip() : "";
            return fromSlash(cmd, arg);
        }
        String low = norm(text);
        if (low.isEmpty()) return Command.of(UNKNOWN);

        Matcher m = COORDS.matcher(low);
        if (m.matches()) return new Command("goto", m.group(1).replace(',', '.') + " " + m.group(2).replace(',', '.'), null, null, low.startsWith("иди"));

        // с аргументом-именем
        for (String[] p : new String[][]{
                {"запомни место ", "remember_place"}, {"запомни торговца ", "remember_vendor"},
                {"иди по маршруту ", "go_route"}, {"дорога ", "go_road"}, {"иди к ", "go_place"}, {"иди в ", "go_place"},
                {"стоп запись ", "record_stop"}, {"профиль ", "profile"},
                {"ошибки ", "errors"}, {"логи ", "logs"}}) {
            if (low.startsWith(p[0]) && low.length() > p[0].length()) {
                return Command.of(p[1], text.substring(p[0].length()).strip());
            }
        }
        if (low.startsWith("качайся")) return grind(low);
        if (low.startsWith("иди качайся")) return Command.of("hunt");
        return switch (low) {
            case "помощь", "команды", "что ты умеешь", "меню" -> Command.of("help");
            case "статус", "где я" -> Command.of("status");
            case "стоп" -> Command.of("stop");
            case "продай", "продать" -> Command.of("sell");
            case "воскресни", "воскреснуть" -> Command.of("revive");
            case "призови питомца", "питомец", "призови пета", "пет" -> Command.of("pet");
            case "сядь на коня", "призови коня", "призови боевого коня", "конь", "маунт" -> Command.of("mount");
            case "слезь", "слезь с коня" -> Command.of("dismount");
            case "отдохни", "отдых" -> Command.of("rest");
            case "лут" -> Command.of("loot");
            case "бей" -> Command.of("fight");
            case "ошибки" -> Command.of("errors");
            case "логи", "журнал" -> Command.of("logs");
            case "сумки" -> Command.of("bags");
            case "места", "места куда", "мои места" -> Command.of("places");
            case "маршруты" -> Command.of("routes");
            case "покажи дороги", "дороги", "список дорог" -> Command.of("roads_list");
            case "запиши дорогу", "запись" -> Command.of("record_start");
            case "стоп запись" -> Command.of("record_stop");
            case "построй дороги" -> Command.of("roads");
            case "ротация" -> Command.of("rotation");
            case "переучи ротацию" -> Command.of("learn");
            case "умения" -> Command.of("spells");
            case "перезагрузи бота", "перезагрузка" -> Command.of("reload");
            case "жди" -> Command.of("wait");
            case "запомни торговца" -> Command.of("remember_vendor_here");
            case "иди качайся" -> Command.of("hunt");
            default -> Command.of(UNKNOWN);
        };
    }

    private static Command fromSlash(String cmd, String arg) {
        return switch (cmd) {
            case "start", "help", "menu" -> Command.of("help");
            case "id" -> Command.of("id");
            case "status" -> Command.of("status");
            case "stop" -> Command.of("stop");
            case "grind" -> grind("качайся " + arg);
            case "patrol" -> grind("качайся по точкам " + arg);
            case "hunt" -> Command.of("hunt");
            case "sell" -> Command.of("sell");
            case "revive" -> Command.of("revive");
            case "pet" -> Command.of("pet");
            case "mount" -> Command.of("mount");
            case "dismount" -> Command.of("dismount");
            case "rest" -> Command.of("rest");
            case "loot" -> Command.of("loot");
            case "fight" -> Command.of("fight");
            case "bags" -> Command.of("bags");
            case "errors" -> arg.isBlank() ? Command.of("errors") : Command.of("errors", arg);
            case "logs" -> arg.isBlank() ? Command.of("logs") : Command.of("logs", arg);
            case "places" -> Command.of("places");
            case "routes" -> Command.of("routes");
            case "roadslist" -> Command.of("roads_list");
            case "road" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("go_road", arg);
            case "goplace" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("go_place", arg);
            case "goroute" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("go_route", arg);
            case "remember" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("remember_place", arg);
            case "vendor" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("remember_vendor", arg);
            case "roads" -> Command.of("roads");
            case "rotation" -> Command.of("rotation");
            case "learn" -> Command.of("learn");
            case "spells" -> Command.of("spells");
            case "profile" -> arg.isBlank() ? Command.of(UNKNOWN) : Command.of("profile", arg);
            case "reload" -> Command.of("reload");
            case "wait" -> Command.of("wait");
            case "recstart" -> Command.of("record_start");
            case "recstop" -> Command.of("record_stop", arg.isBlank() ? null : arg);
            default -> Command.of(UNKNOWN);
        };
    }

    /** «качайся [без лута] [по точкам] [N убийств] [M минут]»: flag = собирать добычу, arg = "patrol" для обхода. */
    private static Command grind(String low) {
        boolean loot = !low.contains("без лута");
        String mode = low.contains("по точкам") ? "patrol" : null;
        Matcher k = KILLS.matcher(low), t = MINUTES.matcher(low);
        Integer kills = k.find() ? Integer.valueOf(k.group(1)) : null;
        Integer minutes = t.find() ? Integer.valueOf(t.group(1)) : null;
        return new Command("grind", mode, kills, minutes, loot);
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
