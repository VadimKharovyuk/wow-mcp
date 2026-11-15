package com.example.wowmcp.telegram;

import com.example.wowmcp.db.GameNotification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotifierTest {

    @Test
    void deathIsSentOnceAndDuplicatesAreSuppressed() {
        TelegramBot bot = mock(TelegramBot.class);
        Notifier n = new Notifier(bot);
        GameNotification death = new GameNotification("death", Map.of("source", "bot", "zone", 117));
        n.on(death);
        n.on(death);                                    // тот же текст в пределах 20 с
        verify(bot, times(1)).broadcast(contains("погиб"));
    }

    @Test
    void onlyEndOfInterestingJobsIsSent() {
        TelegramBot bot = mock(TelegramBot.class);
        Notifier n = new Notifier(bot);
        n.on(new GameNotification("job", Map.of("phase", "start", "name", "grind")));
        n.on(new GameNotification("job", Map.of("phase", "end", "name", "loot", "status", "done")));
        verify(bot, never()).broadcast(anyString());
        n.on(new GameNotification("job", Map.of("phase", "end", "name", "grind", "status", "done", "result", "лимит убийств достигнут")));
        verify(bot).broadcast(contains("Гринд завершён"));
    }
}
