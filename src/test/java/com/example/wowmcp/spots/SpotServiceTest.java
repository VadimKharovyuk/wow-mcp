package com.example.wowmcp.spots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SpotServiceTest {

    /** Репозиторий-заглушка: считает обращения, данные — 1 час работы в клетке (10, 10) зоны 401. */
    private static final class FakeRepo extends SpotRepository {
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;

        FakeRepo() {
            super(null);
        }

        @Override
        public List<Snapshot> snapshots(int hours) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("БД недоступна");
            List<Snapshot> s = new ArrayList<>();
            long xp = 0;
            for (long t = 0; t < 3600; t += 30) { s.add(new Snapshot(t, 71, xp, 1_000_000)); xp += 100; }
            return s;
        }

        @Override
        public List<Kill> kills(int hours) {
            List<Kill> k = new ArrayList<>();
            for (long t = 5; t < 3600; t += 30) k.add(new Kill(t, 401, 10, 10, 71, 71, true));
            return k;
        }

        @Override
        public List<MapPoint> deaths(int hours) {
            return List.of();
        }

        @Override
        public List<MapPoint> stucks(int hours) {
            return List.of();
        }
    }

    @Test
    void resultIsCachedForFiveMinutesAndRecomputedAfter() {
        FakeRepo repo = new FakeRepo();
        AtomicLong now = new AtomicLong(1_000_000);
        SpotService svc = new SpotService(repo, now::get);
        svc.all();
        svc.all();
        assertThat(repo.calls.get()).isEqualTo(1);
        now.addAndGet(SpotService.TTL_MS - 1);
        svc.all();
        assertThat(repo.calls.get()).isEqualTo(1);
        now.addAndGet(2);
        svc.all();
        assertThat(repo.calls.get()).isEqualTo(2);
    }

    @Test
    void bestAndMeasuredCellsFilterByZone() {
        SpotService svc = new SpotService(new FakeRepo(), () -> 0L);
        assertThat(svc.best(401, 5)).hasSize(1);
        assertThat(svc.best(115, 5)).isEmpty();
        assertThat(svc.measuredCells(401)).containsKey(SpotService.keyFor(10.3, 10.9));       // клетка (11, 11)
        assertThat(svc.measuredCells(401)).doesNotContainKey(SpotService.keyFor(50, 50));
        assertThat(svc.measuredCells(115)).isEmpty();
    }

    @Test
    void databaseFailureReturnsEmptyResultOrKeepsOldCache() {
        FakeRepo repo = new FakeRepo();
        AtomicLong now = new AtomicLong(0);
        SpotService svc = new SpotService(repo, now::get);
        repo.fail = true;
        assertThat(svc.all().spots()).isEmpty();                    // ошибки БД не пробрасываются в «иди качайся»
        repo.fail = false;
        svc.all();
        assertThat(svc.best(401, 5)).hasSize(1);
        repo.fail = true;
        now.addAndGet(SpotService.TTL_MS + 1);
        assertThat(svc.all().spots()).hasSize(1);                   // кэш устарел, БД упала — отдаём прежний результат
    }
}
