package com.example.wowmcp.db;

import com.example.wowmcp.game.GameBridge;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpellIdResolverTest {

    private static final String SPELLS = "[{\"nameHash\":1278643508,\"spellId\":3044,\"slot\":4},"
            + "{\"nameHash\":1554409780,\"spellId\":1978,\"slot\":3},{\"nameHash\":5,\"spellId\":0}]";

    @Test
    void resolvesSpellIdByNameHashAndBackfillsOldRows() {
        GameBridge bridge = mock(GameBridge.class);
        CastHistoryRepository casts = mock(CastHistoryRepository.class);
        when(bridge.evalIfReady(anyString(), anyLong())).thenReturn(Optional.of(SPELLS));
        when(casts.backfillSpellId(1278643508, 3044)).thenReturn(12);
        SpellIdResolver resolver = new SpellIdResolver(bridge, casts);

        assertThat(resolver.resolve(1278643508)).isEqualTo(3044);
        assertThat(resolver.resolve(1554409780)).isEqualTo(1978);   // из той же таблицы, без повторного запроса
        verify(bridge, times(1)).evalIfReady(anyString(), anyLong());
        verify(casts).backfillSpellId(1278643508, 3044);
        verify(casts).backfillSpellId(1554409780, 1978);
    }

    @Test
    void unknownSpellIsNullAndTableIsNotRequestedEveryTime() {
        GameBridge bridge = mock(GameBridge.class);
        CastHistoryRepository casts = mock(CastHistoryRepository.class);
        when(bridge.evalIfReady(anyString(), anyLong())).thenReturn(Optional.of(SPELLS));
        SpellIdResolver resolver = new SpellIdResolver(bridge, casts);

        assertThat(resolver.resolve(999)).isNull();
        assertThat(resolver.resolve(999)).isNull();
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve(0)).isNull();
        verify(bridge, times(1)).evalIfReady(anyString(), anyLong());   // не чаще раза в 30 с
    }

    @Test
    void botNotReadyGivesNull() {
        GameBridge bridge = mock(GameBridge.class);
        when(bridge.evalIfReady(anyString(), anyLong())).thenReturn(Optional.empty());
        assertThat(new SpellIdResolver(bridge, mock(CastHistoryRepository.class)).resolve(1)).isNull();
    }
}
