package com.pawbridge.animalservice.reconciliation;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class HistoryCollectorTest {
    @Test void rescans991Reported990ReturnedAndKeepsVerifiedRecordsWithoutClaimingComplete() throws Exception {
        var calls = new AtomicInteger();
        var animals = IntStream.range(0, 990).mapToObj(i -> HistoryPlanTest.animal("id-" + i)).toList();
        var snapshot = new HistoryCollector((month, page) -> {
            calls.incrementAndGet(); return new HistoryCollector.Page(page, 991, animals);
        }).collect(HistoryPlanTest.MONTH);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(snapshot.items()).hasSize(990);
        assertThat(snapshot.warnings()).anyMatch(w -> w.contains("completeness remains unverified"));
    }
    @Test void identicalDuplicatesRemainOneIdentityAndRequireAcknowledgement() throws Exception {
        var animal = HistoryPlanTest.animal("one");
        var snapshot = new HistoryCollector((month, page) -> new HistoryCollector.Page(page, 2, List.of(animal, animal)))
                .collect(HistoryPlanTest.MONTH);
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.warnings()).anyMatch(w -> w.contains("Duplicate"));
    }
    @Test void conflictingDuplicatesAndChangingRescansAreRejected() {
        var first = HistoryPlanTest.animal("one"); var other = HistoryPlanTest.animal("one"); other.setProcessState("보호중");
        assertThatThrownBy(() -> new HistoryCollector((month, page) -> new HistoryCollector.Page(page, 2, List.of(first, other)))
                .collect(HistoryPlanTest.MONTH)).hasMessageContaining("Conflicting");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new HistoryCollector((month, page) -> new HistoryCollector.Page(page, 2,
                List.of(calls.incrementAndGet() == 1 ? first : other))).collect(HistoryPlanTest.MONTH)).hasMessageContaining("changed between");
    }
    @Test void wrongPageAndUnboundedMonthFailImmediately() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new HistoryCollector((month, page) -> {
            calls.incrementAndGet(); return new HistoryCollector.Page(1, 20001, List.of());
        }).collect(HistoryPlanTest.MONTH)).hasMessageContaining("Invalid APMS page");
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> new HistoryCollector((month, page) -> new HistoryCollector.Page(2, 0, List.of()))
                .collect(HistoryPlanTest.MONTH)).hasMessageContaining("Invalid APMS page");
    }
    @Test void historicalMonthIsPassedWithoutRecentStateOrUpdateFilters() throws Exception {
        var calls = new AtomicInteger();
        var snapshot = new HistoryCollector((month, page) -> {
            assertThat(month).isEqualTo(HistoryPlanTest.MONTH);
            calls.incrementAndGet(); return new HistoryCollector.Page(page, 1, List.of(HistoryPlanTest.animal("january")));
        }).collect(HistoryPlanTest.MONTH);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(snapshot.warnings()).isEmpty();
    }
}
