package com.example.wowmcp.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderLockTest {

    @Test
    void withoutDatabaseTheProcessIsAlwaysLeader() {
        Database db = mock(Database.class);
        when(db.isEnabled()).thenReturn(false);
        LeaderLock lock = new LeaderLock(db);
        lock.tick();
        assertThat(lock.isLeader()).isTrue();
    }

    @Test
    void withDatabaseNobodyIsLeaderUntilTheLockIsTaken() {
        Database db = mock(Database.class);
        when(db.isEnabled()).thenReturn(true);
        LeaderLock lock = new LeaderLock(db);
        assertThat(lock.isLeader()).isFalse();           // до первого tick() событий не читаем
    }
}
