package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.repository.AnimalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApmsQueryProgressTest {
    private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final LocalDate FIRST = LocalDate.of(2026, 8, 11);
    private static final LocalDate END = LocalDate.of(2026, 9, 11);
    private final ApmsSyncPlan initial = new ApmsSyncPlan(FIRST, JUNE, FIRST, END);

    @Test
    void givenOneIncompleteMonthAcrossRuns__whenPlannedLater__thenAdvanceHealthyMonthsAndKeepUnresolvedStart() {
        var first = result(1, initial, Set.of(1));
        var next = plan(END.plusDays(10), first);
        assertThat(start(next, JUNE)).isEqualTo(FIRST);
        assertThat(start(next, JULY)).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(next.recentStart()).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(ApmsSyncPlan.from(next.parameters())).isEqualTo(next);
        var second = result(2, next, Set.of(1));
        var later = plan(LocalDate.of(2026, 10, 5), second, first);
        assertThat(start(later, JUNE)).isEqualTo(FIRST);
        assertThat(start(later, JULY)).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(later.queries()).extracting(ApmsSyncPlan.Query::intakeStart).contains(LocalDate.of(2026, 10, 1));
    }

    @Test
    void givenUnresolvedRecentIntakes__whenAgedBeyondThirtyDays__thenKeepUnfilteredRecoveryUntilComplete() {
        var first = result(1, initial, Set.of(0, 1));
        var next = plan(END.plusDays(40), first);
        assertThat(next.recentStart()).isEqualTo(FIRST);
        var recovered = result(2, next, Set.of(1));
        assertThat(plan(next.end().plusDays(1), recovered, first).recentStart()).isEqualTo(next.end().plusDays(1).minusDays(30));
    }

    @Test
    void givenPreviouslyHealthyMonthNowIncomplete__whenOverlapAges__thenRetainItsFailedOverlapStart() {
        var first = result(1, initial, Set.of(1));
        var next = plan(END.plusDays(10), first);
        var second = result(2, next, Set.of(1, 2));
        var later = plan(END.plusDays(40), second, first);
        assertThat(start(later, JULY)).isEqualTo(start(next, JULY));
    }

    @Test
    void givenFullSuccessThenIncompleteOverlap__whenPlanned__thenRecoverEarlierThanFullSuccessDate() {
        var first = result(1, initial, Set.of());
        var next = plan(END.plusDays(1), first);
        var second = result(2, next, Set.of(1));
        var later = plan(END.plusDays(10), second, first);
        assertThat(start(later, JUNE)).isEqualTo(next.updatedStart());
        assertThat(start(later, JULY)).isEqualTo(END.plusDays(10).minusDays(30));
    }

    @Test
    void givenLatestSearchFailure__whenOverlapAges__thenRetainAllAttemptedWindows() {
        var first = result(1, initial, Set.of(1));
        var next = plan(END.plusDays(10), first);
        var second = result(2, next, Set.of(1));
        second.getStepExecutions().stream().filter(step -> step.getStepName().equals("elasticsearchIndexStep"))
                .findFirst().orElseThrow().setStatus(BatchStatus.FAILED);
        var later = plan(END.plusDays(40), second, first);
        assertThat(start(later, JULY)).isEqualTo(start(next, JULY));
        assertThat(later.recentStart()).isEqualTo(next.recentStart());
    }

    @Test
    void givenLaterNarrowQueryAfterGap__whenComplete__thenDoNotAdvanceAcrossUnqueriedDates() {
        var first = result(1, initial, Set.of(1, 2));
        var narrow = new ApmsSyncPlan(FIRST, JUNE, FIRST, END.plusDays(10), Map.of(JULY, FIRST.plusDays(5)));
        assertThat(start(plan(END.plusDays(20), result(2, narrow, Set.of(1)), first), JULY)).isEqualTo(FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTING", "STARTED", "STOPPING", "STOPPED", "UNKNOWN", "ABANDONED"})
    void givenNonfinalExecution__whenPlanned__thenDoNotAdvance(String status) {
        var execution = result(1, initial, Set.of(1));
        execution.setStatus(BatchStatus.valueOf(status));
        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep"})
    void givenDataStepFailure__whenPlanned__thenDoNotAdvance(String name) {
        var execution = result(1, initial, Set.of(1));
        execution.getStepExecutions().stream().filter(step -> step.getStepName().equals(name)).findFirst().orElseThrow().setStatus(BatchStatus.FAILED);
        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "process", "write"})
    void givenSkippedData__whenPlanned__thenDoNotAdvance(String phase) {
        var execution = result(1, initial, Set.of(1));
        var step = execution.getStepExecutions().iterator().next();
        if (phase.equals("read")) step.setReadSkipCount(1);
        if (phase.equals("process")) step.setProcessSkipCount(1);
        if (phase.equals("write")) step.setWriteSkipCount(1);
        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "corrupt", "wrong-query", "wrong-count", "wrong-version", "missing-step"})
    void givenUnverifiableEvidence__whenPlanned__thenRetainOriginalWindow(String defect) {
        var execution = result(1, initial, Set.of(1));
        var context = execution.getExecutionContext();
        switch (defect) {
            case "missing" -> context.remove(ApmsQueryProgress.CONTEXT_KEY);
            case "corrupt" -> context.putString(ApmsQueryProgress.CONTEXT_KEY, "not json");
            case "wrong-query" -> ApmsQueryProgress.store(execution, List.of(new ApmsQueryProgress.Result(initial.queries().get(0), true)));
            case "wrong-count" -> context.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
            case "wrong-version" -> context.putString(ApmsQueryProgress.CONTEXT_KEY, context.getString(ApmsQueryProgress.CONTEXT_KEY).replace("\"version\":1", "\"version\":2"));
            case "missing-step" -> {
                execution = new JobExecution(execution.getJobInstance(), execution.getId(), execution.getJobParameters());
                execution.setStatus(BatchStatus.FAILED);
                execution.setExecutionContext(context);
            }
            default -> throw new AssertionError(defect);
        }
        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST);
    }

    @Test
    void givenCompletedNewContractWithoutEvidence__whenPlanned__thenDoNotUseLegacySuccessFallback() {
        var execution = result(1, initial, Set.of());
        execution.getExecutionContext().remove(ApmsQueryProgress.CONTEXT_KEY);
        assertThat(plan(END.plusDays(40), execution).updatedStart()).isEqualTo(FIRST);
    }

    @Test
    void givenFutureEvidence__whenPlanned__thenReject() {
        assertThatThrownBy(() -> plan(END.minusDays(1), result(1, initial, Set.of(1)))).hasMessageContaining("future");
    }

    @Test
    void givenFullRecovery__whenPlanned__thenUseFullSuccessWithThirtyDayOverlap() {
        assertThat(plan(END.plusDays(10), result(1, initial, Set.of())).updatedStart()).isEqualTo(END.plusDays(10).minusDays(30));
    }

    @Test
    void givenOutOfRangeOrMalformedMonthlyWindows__whenRestored__thenReject() {
        assertThatThrownBy(() -> new ApmsSyncPlan(FIRST, JUNE, FIRST, END, Map.of(JULY, END.plusDays(1))))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = new JobParametersBuilder(initial.parameters()).addString("apms.sync.month.not-a-month", "2026-08-11").toJobParameters();
        assertThatThrownBy(() -> ApmsSyncPlan.from(invalid)).isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    private JobExecution result(long id, ApmsSyncPlan window, Set<Integer> incomplete) {
        var execution = new JobExecution(new JobInstance(id, "apmsAnimalSyncJob"), id, window.parameters());
        execution.setStatus(incomplete.isEmpty() ? BatchStatus.COMPLETED : BatchStatus.FAILED);
        for (String name : List.of("shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep")) {
            execution.createStepExecution(name).setStatus(BatchStatus.COMPLETED);
        }
        execution.createStepExecution("apmsCollectionVerificationStep").setStatus(execution.getStatus());
        var results = new ArrayList<ApmsQueryProgress.Result>();
        for (int i = 0; i < window.queries().size(); i++) results.add(new ApmsQueryProgress.Result(window.queries().get(i), !incomplete.contains(i)));
        ApmsQueryProgress.store(execution, results);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, incomplete.size());
        return execution;
    }

    private ApmsSyncPlan plan(LocalDate today, JobExecution... executions) {
        var explorer = mock(JobExplorer.class);
        when(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100)).thenReturn(Arrays.stream(executions).map(JobExecution::getJobInstance).toList());
        for (var execution : executions) when(explorer.getJobExecutions(execution.getJobInstance())).thenReturn(List.of(execution));
        var animals = mock(AnimalRepository.class);
        when(animals.findEarliestHappenDate(com.pawbridge.animalservice.enums.ApiSource.APMS_ANIMAL)).thenReturn(JUNE);
        return new ApmsSyncPlanFactory(animals, explorer,
                Clock.fixed(today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))).create("apmsAnimalSyncJob");
    }

    private LocalDate start(ApmsSyncPlan plan, LocalDate month) {
        return plan.queries().stream().filter(query -> query.updatedStart() != null && query.intakeStart().equals(month))
                .findFirst().orElseThrow().updatedStart();
    }
}
