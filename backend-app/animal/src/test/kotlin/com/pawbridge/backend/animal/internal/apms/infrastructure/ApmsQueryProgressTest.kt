package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

class ApmsQueryProgressTest {
    private val initial = ApmsSyncPlan(FIRST, JUNE, FIRST, END)

    @Test
    fun givenOneIncompleteMonthAcrossRuns__whenPlannedLater__thenAdvanceHealthyMonthsAndKeepUnresolvedStart() {
        val first = result(1, initial, setOf(1))
        val next = plan(END.plusDays(10), first)
        assertThat(start(next, JUNE)).isEqualTo(FIRST)
        assertThat(start(next, JULY)).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(next.recentStart).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(ApmsSyncPlan.from(next.parameters())).isEqualTo(next)

        val second = result(2, next, setOf(1))
        val later = plan(LocalDate.of(2026, 10, 5), second, first)

        assertThat(start(later, JUNE)).isEqualTo(FIRST)
        assertThat(start(later, JULY)).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(later.queries().map { query -> query.intakeStart }).contains(LocalDate.of(2026, 10, 1))
    }

    @Test
    fun givenCompletedCountMismatch__whenPlannedLater__thenKeepOnlyTheUnresolvedWindowRetryable() {
        val partial = result(1, initial, setOf(1))
        partial.status = BatchStatus.COMPLETED
        partial.stepExecutions.first { step -> step.stepName == "apmsCollectionVerificationStep" }.status =
            BatchStatus.COMPLETED

        val next = plan(END.plusDays(10), partial)

        assertThat(start(next, JUNE)).isEqualTo(FIRST)
        assertThat(start(next, JULY)).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(next.recentStart).isEqualTo(LocalDate.of(2026, 8, 22))
    }

    @Test
    fun givenUnresolvedRecentIntakes__whenAgedBeyondThirtyDays__thenKeepUnfilteredRecoveryUntilComplete() {
        val first = result(1, initial, setOf(0, 1))
        val next = plan(END.plusDays(40), first)
        assertThat(next.recentStart).isEqualTo(FIRST)

        val recovered = result(2, next, setOf(1))

        assertThat(plan(next.end.plusDays(1), recovered, first).recentStart)
            .isEqualTo(next.end.plusDays(1).minusDays(30))
    }

    @Test
    fun givenPreviouslyHealthyMonthNowIncomplete__whenOverlapAges__thenRetainItsFailedOverlapStart() {
        val first = result(1, initial, setOf(1))
        val next = plan(END.plusDays(10), first)
        val second = result(2, next, setOf(1, 2))

        val later = plan(END.plusDays(40), second, first)

        assertThat(start(later, JULY)).isEqualTo(start(next, JULY))
    }

    @Test
    fun givenFullSuccessThenIncompleteOverlap__whenPlanned__thenRecoverEarlierThanFullSuccessDate() {
        val first = result(1, initial, setOf())
        val next = plan(END.plusDays(1), first)
        val second = result(2, next, setOf(1))

        val later = plan(END.plusDays(10), second, first)

        assertThat(start(later, JUNE)).isEqualTo(next.updatedStart)
        assertThat(start(later, JULY)).isEqualTo(END.plusDays(10).minusDays(30))
    }

    @Test
    fun givenLatestSearchFailure__whenOverlapAges__thenRetainAllAttemptedWindows() {
        val first = result(1, initial, setOf(1))
        val next = plan(END.plusDays(10), first)
        val second = result(2, next, setOf(1))
        second.stepExecutions.first { step -> step.stepName == "elasticsearchIndexStep" }.status = BatchStatus.FAILED

        val later = plan(END.plusDays(40), second, first)

        assertThat(start(later, JULY)).isEqualTo(start(next, JULY))
        assertThat(later.recentStart).isEqualTo(next.recentStart)
    }

    @Test
    fun givenLaterNarrowQueryAfterGap__whenComplete__thenDoNotAdvanceAcrossUnqueriedDates() {
        val first = result(1, initial, setOf(1, 2))
        val narrow = ApmsSyncPlan(FIRST, JUNE, FIRST, END.plusDays(10), mapOf(JULY to FIRST.plusDays(5)))

        assertThat(start(plan(END.plusDays(20), result(2, narrow, setOf(1)), first), JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STARTING", "STARTED", "STOPPING", "STOPPED", "UNKNOWN", "ABANDONED"])
    fun givenNonfinalExecution__whenPlanned__thenDoNotAdvance(status: String) {
        val execution = result(1, initial, setOf(1))
        execution.status = BatchStatus.valueOf(status)

        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep"])
    fun givenDataStepFailure__whenPlanned__thenDoNotAdvance(name: String) {
        val execution = result(1, initial, setOf(1))
        execution.stepExecutions.first { step -> step.stepName == name }.status = BatchStatus.FAILED

        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["read", "process", "write"])
    fun givenSkippedData__whenPlanned__thenDoNotAdvance(phase: String) {
        val execution = result(1, initial, setOf(1))
        val step = execution.stepExecutions.iterator().next()
        if (phase == "read") {
            step.setReadSkipCount(1)
        }
        if (phase == "process") {
            step.setProcessSkipCount(1)
        }
        if (phase == "write") {
            step.setWriteSkipCount(1)
        }

        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "corrupt", "wrong-query", "wrong-count", "wrong-version", "missing-step"])
    fun givenUnverifiableEvidence__whenPlanned__thenRetainOriginalWindow(defect: String) {
        var execution = result(1, initial, setOf(1))
        val context = execution.executionContext
        when (defect) {
            "missing" -> context.remove(ApmsQueryProgress.CONTEXT_KEY)
            "corrupt" -> context.putString(ApmsQueryProgress.CONTEXT_KEY, "not json")
            "wrong-query" -> ApmsQueryProgress.store(
                execution,
                listOf(ApmsQueryProgress.Result(initial.queries()[0], true)),
            )
            "wrong-count" -> context.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
            "wrong-version" -> context.putString(
                ApmsQueryProgress.CONTEXT_KEY,
                context.getString(ApmsQueryProgress.CONTEXT_KEY).replace("\"version\":1", "\"version\":2"),
            )
            "missing-step" -> {
                execution = JobExecution(execution.jobInstance, execution.id, execution.jobParameters)
                execution.status = BatchStatus.FAILED
                execution.executionContext = context
            }
            else -> throw AssertionError(defect)
        }

        assertThat(start(plan(END.plusDays(10), execution), JULY)).isEqualTo(FIRST)
    }

    @Test
    fun givenCompletedNewContractWithoutEvidence__whenPlanned__thenDoNotUseLegacySuccessFallback() {
        val execution = result(1, initial, setOf())
        execution.executionContext.remove(ApmsQueryProgress.CONTEXT_KEY)

        assertThat(plan(END.plusDays(40), execution).updatedStart).isEqualTo(FIRST)
    }

    @Test
    fun givenFutureEvidence__whenPlanned__thenReject() {
        assertThatThrownBy { plan(END.minusDays(1), result(1, initial, setOf(1))) }.hasMessageContaining("future")
    }

    @Test
    fun givenFullRecovery__whenPlanned__thenUseFullSuccessWithThirtyDayOverlap() {
        assertThat(plan(END.plusDays(10), result(1, initial, setOf())).updatedStart)
            .isEqualTo(END.plusDays(10).minusDays(30))
    }

    @Test
    fun givenOutOfRangeOrMalformedMonthlyWindows__whenRestored__thenReject() {
        assertThatThrownBy { ApmsSyncPlan(FIRST, JUNE, FIRST, END, mapOf(JULY to END.plusDays(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val invalid = JobParametersBuilder(initial.parameters())
            .addString("apms.sync.month.not-a-month", "2026-08-11")
            .toJobParameters()

        assertThatThrownBy { ApmsSyncPlan.from(invalid) }.isInstanceOf(DateTimeParseException::class.java)
    }

    private fun result(id: Long, window: ApmsSyncPlan, incomplete: Set<Int>): JobExecution {
        val execution = JobExecution(JobInstance(id, "apmsAnimalSyncJob"), id, window.parameters())
        execution.status = if (incomplete.isEmpty()) BatchStatus.COMPLETED else BatchStatus.FAILED
        for (name in listOf("shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep")) {
            execution.createStepExecution(name).status = BatchStatus.COMPLETED
        }
        execution.createStepExecution("apmsCollectionVerificationStep").status = execution.status
        val results = ArrayList<ApmsQueryProgress.Result>()
        for (i in 0 until window.queries().size) {
            results.add(ApmsQueryProgress.Result(window.queries()[i], !incomplete.contains(i)))
        }
        ApmsQueryProgress.store(execution, results)
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, incomplete.size)
        return execution
    }

    private fun plan(today: LocalDate, vararg executions: JobExecution): ApmsSyncPlan {
        val explorer = mock(JobExplorer::class.java)
        `when`(explorer.getJobInstances("apmsAnimalSyncJob", 0, 100))
            .thenReturn(executions.map { execution -> execution.jobInstance })
        for (execution in executions) {
            `when`(explorer.getJobExecutions(execution.jobInstance)).thenReturn(listOf(execution))
        }
        val animals = mock(AnimalRepository::class.java)
        `when`(animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL)).thenReturn(JUNE)
        return ApmsSyncPlanFactory(
            animals,
            explorer,
            Clock.fixed(today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
        ).create("apmsAnimalSyncJob")
    }

    private fun start(plan: ApmsSyncPlan, month: LocalDate): LocalDate? {
        return plan.queries()
            .first { query -> query.updatedStart != null && query.intakeStart == month }
            .updatedStart
    }

    companion object {
        private val JUNE: LocalDate = LocalDate.of(2026, 6, 1)
        private val JULY: LocalDate = LocalDate.of(2026, 7, 1)
        private val FIRST: LocalDate = LocalDate.of(2026, 8, 11)
        private val END: LocalDate = LocalDate.of(2026, 9, 11)
    }
}
