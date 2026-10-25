package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class ApmsSyncPlanFactoryTest {
    @Mock
    private lateinit var animals: AnimalRepository

    @Mock
    private lateinit var explorer: JobExplorer

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-09T15:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var factory: ApmsSyncPlanFactory

    @BeforeEach
    fun setUp() {
        factory = ApmsSyncPlanFactory(animals, explorer, clock)
    }

    @Test
    fun givenEmptyDatabaseAtKstMidnight__whenPlanned__thenKeepRecentIntakesAndFixedKstDate() {
        val plan = factory.create("apmsAnimalSyncJob")

        assertThat(plan.end).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(plan.recentStart).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(plan.updatedStart).isEqualTo(plan.recentStart)
        assertThat(ApmsSyncPlan.from(plan.parameters())).isEqualTo(plan)
        assertThat(plan.queries().map { query -> query.intakeStart })
            .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
    }

    @Test
    fun givenHistoricalRecordsAcrossYears__whenPlanned__thenCoverEveryMonthIncludingGaps() {
        `when`(animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL)).thenReturn(LocalDate.of(2025, 12, 15))

        val plan = factory.create("apmsAnimalSyncJob")

        assertThat(plan.historyStart).isEqualTo(LocalDate.of(2025, 12, 1))
        assertThat(plan.queries()).hasSize(11)
        assertThat(plan.queries()[1].intakeEnd).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(plan.queries()[2].intakeStart).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(plan.queries()[10].intakeEnd).isEqualTo(plan.end)
    }

    @Test
    fun givenOldSuccessfulUpdatedRunAndNewFailedRuns__whenPlanned__thenResumeFromSuccessWithDayOverlap() {
        val failed = execution(4L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true)
        val unknown = execution(3L, BatchStatus.UNKNOWN, LocalDate.of(2026, 9, 8), true)
        val success = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true)
        history(failed, unknown, success)

        assertThat(factory.create("apmsAnimalSyncJob").updatedStart).isEqualTo(LocalDate.of(2026, 7, 1))
    }

    @Test
    fun givenOnlyLegacySuccess__whenPlanned__thenDoNotTreatItAsModifiedCollectionCheckpoint() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 1, 1), false))

        assertThat(factory.create("apmsAnimalSyncJob").updatedStart).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    @Test
    fun givenRecentSuccess__whenPlanned__thenRetainThirtyDayOverlapForProviderDelay() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true))

        assertThat(factory.create("apmsAnimalSyncJob").updatedStart).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    @Test
    fun givenFutureSuccessfulWindow__whenPlanned__thenFailInsteadOfSkippingUpdates() {
        history(execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 11), true))

        assertThatThrownBy { factory.create("apmsAnimalSyncJob") }.hasMessageContaining("future")
    }

    @Test
    fun givenMetadataUnavailable__whenPlanned__thenFailInsteadOfAssumingBootstrap() {
        `when`(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100))
            .thenThrow(IllegalStateException("metadata unavailable"))

        assertThatThrownBy { factory.create("apmsAnimalSyncJob") }.hasMessageContaining("metadata unavailable")
    }

    @Test
    fun givenNoSuccessAndFirstAttemptOverThirtyDaysAgo__whenPlanned__thenRetainOriginalWindow() {
        history(
            execution(2L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true),
            execution(1L, BatchStatus.FAILED, LocalDate.of(2026, 7, 1), true),
        )

        val plan = factory.create("apmsAnimalSyncJob")

        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(plan.historyStart).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun givenCompletedRunWithSkippedItem__whenPlanned__thenDoNotAdvancePastPreviousSuccess() {
        val skipped = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true)
        skipped.createStepExecution("ingest").setReadSkipCount(1)
        history(skipped, execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true))

        assertThat(factory.create("apmsAnimalSyncJob").updatedStart).isEqualTo(LocalDate.of(2026, 7, 1))
    }

    private fun execution(id: Long, status: BatchStatus, end: LocalDate, current: Boolean): JobExecution {
        val plan = ApmsSyncPlan(end.minusDays(30), end.minusDays(30).withDayOfMonth(1), end.minusDays(30), end)
        val parameters = if (current) {
            JobParametersBuilder(plan.parameters())
                .addString(ApmsSyncPlan.CONTRACT_KEY, ApmsSyncPlan.LEGACY_CONTRACT)
                .toJobParameters()
        } else {
            JobParameters()
        }
        val execution = JobExecution(JobInstance(id, "apmsAnimalSyncJob"), id, parameters)
        execution.status = status
        return execution
    }

    private fun history(vararg executions: JobExecution) {
        `when`(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100))
            .thenReturn(executions.map { execution -> execution.jobInstance })
        for (execution in executions) {
            `when`(explorer.getJobExecutions(execution.jobInstance)).thenReturn(listOf(execution))
        }
    }
}
