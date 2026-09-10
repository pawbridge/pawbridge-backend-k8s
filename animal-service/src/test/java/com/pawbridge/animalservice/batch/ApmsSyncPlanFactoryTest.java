package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.repository.AnimalRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsSyncPlanFactoryTest {
    @Mock private AnimalRepository animals;
    @Mock private JobExplorer explorer;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    private ApmsSyncPlanFactory factory;

    @BeforeEach
    void setUp() { factory = new ApmsSyncPlanFactory(animals, explorer, clock); }

    @Test
    void givenEmptyDatabaseAtKstMidnight__whenPlanned__thenKeepRecentIntakesAndFixedKstDate() {
        var plan = factory.create("apmsAnimalSyncJob");
        assertThat(plan.end()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(plan.recentStart()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(plan.updatedStart()).isEqualTo(plan.recentStart());
        assertThat(ApmsSyncPlan.from(plan.parameters())).isEqualTo(plan);
        assertThat(plan.queries()).extracting(ApmsSyncPlan.Query::intakeStart)
                .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
    }

    @Test
    void givenHistoricalRecordsAcrossYears__whenPlanned__thenCoverEveryMonthIncludingGaps() {
        when(animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL)).thenReturn(LocalDate.of(2025, 12, 15));
        var plan = factory.create("apmsAnimalSyncJob");
        assertThat(plan.historyStart()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(plan.queries()).hasSize(11);
        assertThat(plan.queries().get(1).intakeEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(plan.queries().get(2).intakeStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(plan.queries().get(10).intakeEnd()).isEqualTo(plan.end());
    }

    @Test
    void givenOldSuccessfulUpdatedRunAndNewFailedRuns__whenPlanned__thenResumeFromSuccessWithDayOverlap() {
        var failed = execution(4L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true);
        var unknown = execution(3L, BatchStatus.UNKNOWN, LocalDate.of(2026, 9, 8), true);
        var success = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true);
        history(failed, unknown, success);
        assertThat(factory.create("apmsAnimalSyncJob").updatedStart()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void givenOnlyLegacySuccess__whenPlanned__thenDoNotTreatItAsModifiedCollectionCheckpoint() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 1, 1), false));
        assertThat(factory.create("apmsAnimalSyncJob").updatedStart()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    void givenRecentSuccess__whenPlanned__thenRetainThirtyDayOverlapForProviderDelay() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true));
        assertThat(factory.create("apmsAnimalSyncJob").updatedStart()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    void givenFutureSuccessfulWindow__whenPlanned__thenFailInsteadOfSkippingUpdates() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 11), true));
        assertThatThrownBy(() -> factory.create("apmsAnimalSyncJob")).hasMessageContaining("future");
    }

    @Test
    void givenMetadataUnavailable__whenPlanned__thenFailInsteadOfAssumingBootstrap() {
        when(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100)).thenThrow(new IllegalStateException("metadata unavailable"));
        assertThatThrownBy(() -> factory.create("apmsAnimalSyncJob")).hasMessageContaining("metadata unavailable");
    }

    @Test
    void givenNoSuccessAndFirstAttemptOverThirtyDaysAgo__whenPlanned__thenRetainOriginalWindow() {
        history(execution(2L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true),
                execution(1L, BatchStatus.FAILED, LocalDate.of(2026, 7, 1), true));
        var plan = factory.create("apmsAnimalSyncJob");
        assertThat(plan.updatedStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(plan.historyStart()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void givenCompletedRunWithSkippedItem__whenPlanned__thenDoNotAdvancePastPreviousSuccess() {
        var skipped = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true);
        skipped.createStepExecution("ingest").setReadSkipCount(1);
        history(skipped, execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true));
        assertThat(factory.create("apmsAnimalSyncJob").updatedStart()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    private JobExecution execution(long id, BatchStatus status, LocalDate end, boolean current) {
        var plan = new ApmsSyncPlan(end.minusDays(30), end.minusDays(30).withDayOfMonth(1), end.minusDays(30), end);
        var execution = new JobExecution(new JobInstance(id, "apmsAnimalSyncJob"), id,
                current ? plan.parameters() : new JobParameters());
        execution.setStatus(status);
        return execution;
    }

    private void history(JobExecution... executions) {
        when(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100))
                .thenReturn(java.util.Arrays.stream(executions).map(JobExecution::getJobInstance).toList());
        for (var execution : executions) {
            when(explorer.getJobExecutions(execution.getJobInstance())).thenReturn(List.of(execution));
        }
    }
}
