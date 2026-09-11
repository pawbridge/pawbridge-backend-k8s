package com.pawbridge.animalservice.reconciliation;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HistoryPlanTest {
    @TempDir Path directory;
    static final YearMonth MONTH = YearMonth.of(2026, 1);
    static ApmsAnimal animal(String number) {
        return ApmsAnimal.builder().desertionNo(number).noticeNo("shared-notice")
                .happenDt("20260102").noticeSdt("20260103").noticeEdt("20260113")
                .updTm("2026-09-10 01:02:03.123456").processState("종료(입양)")
                .careRegNo("history-shelter").upKindCd("417000").sexCd("M").neuterYn("U")
                .kindNm("[개] 믹스견").age("2024(년생)").weight("3(Kg)")
                .colorCd("흰색").specialMark("test mark").happenPlace("test place")
                .popfile1("https://example.test/one.jpg").popfile2("https://example.test/two.jpg").build();
    }
    static HistoryPlan plan(List<HistoryPlan.Entry> entries, List<String> warnings) {
        return new HistoryPlan(HistoryPlan.CONTRACT, MONTH.toString(), Instant.now(), "db/schema", "cluster/index/uuid",
                entries.size(), entries.size(), warnings, entries);
    }
    @Test void rejectsWrongMonthAndInvalidCalendarDate() {
        var source = animal("one"); source.setHappenDt("20260201");
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).hasMessageContaining("outside");
        source.setHappenDt("20260132");
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).isInstanceOf(java.time.DateTimeException.class);
    }
    @Test void rejectsMissingAndOverlengthRequiredFields() {
        var source = animal("one"); source.setNoticeNo(null);
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).hasMessageContaining("missing");
        source.setNoticeNo("x".repeat(101));
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).hasMessageContaining("limit");
    }
    @Test void rejectsUnknownStateAndUnrepresentableVersion() {
        var source = animal("one"); source.setProcessState("보호인데알수없음");
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).hasMessageContaining("Unknown");
        source.setProcessState("공고중"); source.setUpdTm("2026-09-10 01:02:03.1234567");
        assertThatThrownBy(() -> HistoryPlan.validateSource(source, MONTH)).hasMessageContaining("precision");
    }
    @Test void rejectsReversedNoticeDatesForBothNewAndExistingCandidates() {
        var source = animal("one"); source.setNoticeSdt("20260131"); source.setNoticeEdt("20260130");
        var before = new HistoryPlan.State(1L, "PROTECT", "보호중", LocalDate.of(2026, 1, 2),
                LocalDateTime.of(2026, 1, 3, 0, 0));
        for (var entry : List.of(new HistoryPlan.Entry(source, null, 1), new HistoryPlan.Entry(source, before, 1))) {
            assertThatThrownBy(() -> plan(List.of(entry), List.of()).validate()).hasMessage("Invalid notice dates");
        }
    }
    @Test void rejectsDuplicateIdentityButAllowsRepeatedNoticeNumber() {
        var first = new HistoryPlan.Entry(animal("one"), null, 1);
        plan(List.of(first, new HistoryPlan.Entry(animal("two"), null, 1)), List.of()).validate();
        assertThatThrownBy(() -> plan(List.of(first, first), List.of()).validate()).hasMessageContaining("Duplicate");
    }
    @Test void rejectsOlderAndEqualConflictingSource() {
        var source = animal("one");
        var before = new HistoryPlan.State(1L, "PROTECT", "보호중", LocalDate.of(2026, 1, 2),
                HistoryPlan.after(source, 1L).sourceUpdatedAt());
        assertThatThrownBy(() -> plan(List.of(new HistoryPlan.Entry(source, before, 1)), List.of()).validate()).hasMessageContaining("not newer");
        source.setUpdTm("2026-01-01 00:00:00");
        assertThatThrownBy(() -> plan(List.of(new HistoryPlan.Entry(source, before, 1)), List.of()).validate()).hasMessageContaining("not newer");
    }
    @Test void requiresExactHashMonthTargetBoundAndIncompleteAcknowledgement() throws Exception {
        Path file = directory.resolve("plan.json");
        byte[] bytes = HistoryPlan.JSON.writeValueAsBytes(plan(List.of(new HistoryPlan.Entry(animal("one"), null, 1)), List.of("count mismatch")));
        Files.write(file, bytes);
        String hash = HistoryJournal.hash(bytes);
        assertThatThrownBy(() -> HistoryReconciliation.approvedPlan(file, "bad", MONTH, 1, true)).hasMessageContaining("hash");
        assertThatThrownBy(() -> HistoryReconciliation.approvedPlan(file, hash, MONTH.plusMonths(1), 1, true)).hasMessageContaining("scope");
        assertThatThrownBy(() -> HistoryReconciliation.approvedPlan(file, hash, MONTH, 0, true)).hasMessageContaining("scope");
        assertThatThrownBy(() -> HistoryReconciliation.approvedPlan(file, hash, MONTH, 1, false)).hasMessageContaining("acknowledgement");
        assertThat(HistoryReconciliation.approvedPlan(file, hash, MONTH, 1, true).entries()).hasSize(1);
    }
    @Test void refusesJournalReuseWithDifferentPlan() throws Exception {
        Path file = directory.resolve("journal.jsonl");
        try (var journal = new HistoryJournal(file, "a")) { journal.event("DB_COMMITTED", Map.of("id", 1)); }
        assertThatThrownBy(() -> new HistoryJournal(file, "b")).hasMessageContaining("another plan");
        try (var journal = new HistoryJournal(file, "a")) { journal.event("COMPLETED", Map.of()); }
        assertThat(Files.readString(file)).contains("DB_COMMITTED", "COMPLETED");
    }
    @Test void rejectsAmbiguousCliOptionsAndUnsafeModesBeforeConnections() {
        assertThatThrownBy(() -> HistoryReconciliationCli.options(new String[]{"--mode", "apply", "--mode", "plan"})).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> HistoryReconciliationCli.options(new String[]{"--allow-incomplete-source", "yes"})).hasMessageContaining("true or false");
        assertThatThrownBy(() -> HistoryReconciliationCli.run(new String[]{"--mode", "delete", "--month", "2026-01", "--plan", "absent.json"}, Map.of()))
                .hasMessageContaining("Mode");
    }
}
