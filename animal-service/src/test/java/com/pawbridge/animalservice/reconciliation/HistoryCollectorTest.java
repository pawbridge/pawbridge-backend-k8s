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
    @Test void preservesUnselectedFieldsUntilCandidateValidationWithoutDroppingRecords() throws Exception {
        var existing = HistoryPlanTest.animal("existing");
        existing.setNoticeSdt("20260131"); existing.setNoticeEdt("20260130");
        var missing = HistoryPlanTest.animal("missing");
        var snapshot = new HistoryCollector((month, page) ->
                new HistoryCollector.Page(page, 2, List.of(existing, missing))).collect(HistoryPlanTest.MONTH);
        assertThat(snapshot.items()).containsExactly(existing, missing);
        assertThat(snapshot.reported()).isEqualTo(2);
        assertThat(snapshot.warnings()).isEmpty();
        assertThat(existing.getNoticeEdt()).isEqualTo("20260130");
    }
    @Test void rejectsMissingOrOverlengthIdentityBeforeCountingRecords() {
        for (String number : Arrays.asList(null, "", " ", "x".repeat(51))) {
            var source = HistoryPlanTest.animal(number);
            assertThatThrownBy(() -> new HistoryCollector((month, page) ->
                    new HistoryCollector.Page(page, 1, List.of(source))).collect(HistoryPlanTest.MONTH))
                    .isInstanceOf(HistoryPlan.RejectedPlanException.class);
        }
    }
    @Test void rejectsInvalidIntakeDatesAndRecordsOutsideRequestedMonth() {
        var source = HistoryPlanTest.animal("one"); source.setHappenDt("20260201");
        var collector = new HistoryCollector((month, page) -> new HistoryCollector.Page(page, 1, List.of(source)));
        assertThatThrownBy(() -> collector.collect(HistoryPlanTest.MONTH)).hasMessageContaining("outside");
        source.setHappenDt("20260132");
        assertThatThrownBy(() -> collector.collect(HistoryPlanTest.MONTH)).isInstanceOf(java.time.DateTimeException.class);
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
