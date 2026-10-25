package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    private val animals = mockk<AnimalRepository>()
    private val explorer = mockk<JobExplorer>()
    private val initial = ApmsSyncPlan(FIRST, JUNE, FIRST, END)

    /** Job history the explorer stub reads when a plan is created; tests replace it between plans. */
    private var history: List<JobExecution> = listOf()

    @Test
    fun `한 달이 실행 사이에 계속 불완전할 때__나중에 계획하면__정상 달은 앞으로 당기고 미해결 달의 시작은 유지한다`() {
        // given
        val first = result(1, initial, setOf(1))
        history = listOf(first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val next = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(next, JUNE)).isEqualTo(FIRST)
        assertThat(start(next, JULY)).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(next.recentStart).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(ApmsSyncPlan.from(next.parameters())).isEqualTo(next)

        // given
        val second = result(2, next, setOf(1))
        history = listOf(second, first)

        // when
        val later = factory(LocalDate.of(2026, 10, 5)).create(JOB)

        // then
        assertThat(start(later, JUNE)).isEqualTo(FIRST)
        assertThat(start(later, JULY)).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(later.queries().map { query -> query.intakeStart }).contains(LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `건수 불일치로 완료된 실행이 있을 때__나중에 계획하면__미해결 구간만 재시도 대상으로 남긴다`() {
        // given
        val partial = result(1, initial, setOf(1))
        partial.status = BatchStatus.COMPLETED
        partial.stepExecutions.first { step -> step.stepName == "apmsCollectionVerificationStep" }.status =
            BatchStatus.COMPLETED
        history = listOf(partial)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val next = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(next, JUNE)).isEqualTo(FIRST)
        assertThat(start(next, JULY)).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(next.recentStart).isEqualTo(LocalDate.of(2026, 8, 22))
    }

    @Test
    fun `최근 입소 건이 미해결인 채 30일을 넘길 때__계획하면__완료될 때까지 필터 없는 복구를 유지한다`() {
        // given
        val first = result(1, initial, setOf(0, 1))
        history = listOf(first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val next = factory(END.plusDays(40)).create(JOB)

        // then
        assertThat(next.recentStart).isEqualTo(FIRST)

        // given
        val recovered = result(2, next, setOf(1))
        history = listOf(recovered, first)

        // when
        val later = factory(next.end.plusDays(1)).create(JOB)

        // then
        assertThat(later.recentStart).isEqualTo(next.end.plusDays(1).minusDays(30))
    }

    @Test
    fun `이전에 정상이던 달이 이번에 불완전할 때__겹침 구간이 오래되면__그 달의 실패한 겹침 시작을 유지한다`() {
        // given
        val first = result(1, initial, setOf(1))
        history = listOf(first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        val next = factory(END.plusDays(10)).create(JOB)
        val second = result(2, next, setOf(1, 2))
        history = listOf(second, first)

        // when
        val later = factory(END.plusDays(40)).create(JOB)

        // then
        assertThat(start(later, JULY)).isEqualTo(start(next, JULY))
    }

    @Test
    fun `전체 성공 뒤 겹침 구간이 불완전할 때__계획하면__전체 성공일보다 앞에서 복구한다`() {
        // given
        val first = result(1, initial, setOf())
        history = listOf(first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        val next = factory(END.plusDays(1)).create(JOB)
        val second = result(2, next, setOf(1))
        history = listOf(second, first)

        // when
        val later = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(later, JUNE)).isEqualTo(next.updatedStart)
        assertThat(start(later, JULY)).isEqualTo(END.plusDays(10).minusDays(30))
    }

    @Test
    fun `최근 검색이 실패했을 때__겹침 구간이 오래되면__시도한 모든 구간을 유지한다`() {
        // given
        val first = result(1, initial, setOf(1))
        history = listOf(first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        val next = factory(END.plusDays(10)).create(JOB)
        val second = result(2, next, setOf(1))
        second.stepExecutions.first { step -> step.stepName == "elasticsearchIndexStep" }.status = BatchStatus.FAILED
        history = listOf(second, first)

        // when
        val later = factory(END.plusDays(40)).create(JOB)

        // then
        assertThat(start(later, JULY)).isEqualTo(start(next, JULY))
        assertThat(later.recentStart).isEqualTo(next.recentStart)
    }

    @Test
    fun `공백 뒤 더 좁은 조회가 완료됐을 때__계획하면__조회하지 않은 날짜를 건너뛰어 당기지 않는다`() {
        // given
        val first = result(1, initial, setOf(1, 2))
        val narrow = ApmsSyncPlan(FIRST, JUNE, FIRST, END.plusDays(10), mapOf(JULY to FIRST.plusDays(5)))
        history = listOf(result(2, narrow, setOf(1)), first)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(20)).create(JOB)

        // then
        assertThat(start(plan, JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STARTING", "STARTED", "STOPPING", "STOPPED", "UNKNOWN", "ABANDONED"])
    fun `최종 상태가 아닌 실행일 때__계획하면__앞으로 당기지 않는다`(status: String) {
        // given
        val execution = result(1, initial, setOf(1))
        execution.status = BatchStatus.valueOf(status)
        history = listOf(execution)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(plan, JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["shelterPrepStep", "apmsAnimalSyncStep", "elasticsearchIndexStep"])
    fun `데이터 단계가 실패했을 때__계획하면__앞으로 당기지 않는다`(name: String) {
        // given
        val execution = result(1, initial, setOf(1))
        execution.stepExecutions.first { step -> step.stepName == name }.status = BatchStatus.FAILED
        history = listOf(execution)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(plan, JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["read", "process", "write"])
    fun `건너뛴 데이터가 있을 때__계획하면__앞으로 당기지 않는다`(phase: String) {
        // given
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
        history = listOf(execution)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(plan, JULY)).isEqualTo(FIRST)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "corrupt", "wrong-query", "wrong-count", "wrong-version", "missing-step"])
    fun `검증할 수 없는 증거일 때__계획하면__원래 구간을 유지한다`(defect: String) {
        // given
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
        history = listOf(execution)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(start(plan, JULY)).isEqualTo(FIRST)
    }

    @Test
    fun `새 계약으로 완료됐지만 증거가 없을 때__계획하면__예전 성공 대체 경로를 쓰지 않는다`() {
        // given
        val execution = result(1, initial, setOf())
        execution.executionContext.remove(ApmsQueryProgress.CONTEXT_KEY)
        history = listOf(execution)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(40)).create(JOB)

        // then
        assertThat(plan.updatedStart).isEqualTo(FIRST)
    }

    @Test
    fun `미래 증거가 있을 때__계획하면__거부한다`() {
        // given
        history = listOf(result(1, initial, setOf(1)))

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val thrown = assertThatThrownBy { factory(END.minusDays(1)).create(JOB) }

        // then
        thrown.hasMessageContaining("future")
    }

    @Test
    fun `전체 복구였을 때__계획하면__전체 성공일에서 30일 겹침을 둔다`() {
        // given
        history = listOf(result(1, initial, setOf()))

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns JUNE
        every { explorer.getJobInstances(JOB, any(), 100) } answers {
            if (secondArg<Int>() == 0) history.map { execution -> execution.jobInstance } else listOf()
        }
        every { explorer.getJobExecutions(any()) } answers {
            val requested = firstArg<JobInstance>()
            history.filter { execution -> execution.jobInstance.instanceId == requested.instanceId }
        }

        // when
        val plan = factory(END.plusDays(10)).create(JOB)

        // then
        assertThat(plan.updatedStart).isEqualTo(END.plusDays(10).minusDays(30))
    }

    @Test
    fun `범위를 벗어나거나 형식이 잘못된 월별 구간일 때__복원하면__거부한다`() {
        // given
        val invalid = JobParametersBuilder(initial.parameters())
            .addString("apms.sync.month.not-a-month", "2026-08-11")
            .toJobParameters()

        // when
        val outOfRange = assertThatThrownBy { ApmsSyncPlan(FIRST, JUNE, FIRST, END, mapOf(JULY to END.plusDays(1))) }
        val malformed = assertThatThrownBy { ApmsSyncPlan.from(invalid) }

        // then
        outOfRange.isInstanceOf(IllegalArgumentException::class.java)
        malformed.isInstanceOf(DateTimeParseException::class.java)
    }

    private fun result(id: Long, window: ApmsSyncPlan, incomplete: Set<Int>): JobExecution {
        val execution = JobExecution(JobInstance(id, JOB), id, window.parameters())
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

    private fun factory(today: LocalDate): ApmsSyncPlanFactory {
        return ApmsSyncPlanFactory(
            animals,
            explorer,
            Clock.fixed(today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
        )
    }

    private fun start(plan: ApmsSyncPlan, month: LocalDate): LocalDate? {
        return plan.queries()
            .first { query -> query.updatedStart != null && query.intakeStart == month }
            .updatedStart
    }

    companion object {
        private const val JOB = "apmsAnimalSyncJob"
        private val JUNE: LocalDate = LocalDate.of(2026, 6, 1)
        private val JULY: LocalDate = LocalDate.of(2026, 7, 1)
        private val FIRST: LocalDate = LocalDate.of(2026, 8, 11)
        private val END: LocalDate = LocalDate.of(2026, 9, 11)
    }
}
