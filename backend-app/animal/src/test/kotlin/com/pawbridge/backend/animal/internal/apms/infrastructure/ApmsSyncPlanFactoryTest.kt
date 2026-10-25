package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

class ApmsSyncPlanFactoryTest {
    private val animals = mockk<AnimalRepository>()
    private val explorer = mockk<JobExplorer>()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-09T15:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var factory: ApmsSyncPlanFactory

    @BeforeEach
    fun setUp() {
        factory = ApmsSyncPlanFactory(animals, explorer, clock)
    }

    @Test
    fun `비어 있는 데이터베이스가 KST 자정에 있을 때__계획하면__최근 입소분과 고정된 KST 날짜를 유지한다`() {
        // given
        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns listOf()

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.end).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(plan.recentStart).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(plan.updatedStart).isEqualTo(plan.recentStart)
        assertThat(ApmsSyncPlan.from(plan.parameters())).isEqualTo(plan)
        assertThat(plan.queries().map { query -> query.intakeStart })
            .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
    }

    @Test
    fun `여러 해에 걸친 과거 기록이 있을 때__계획하면__빈 달을 포함해 모든 달을 덮는다`() {
        // given
        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns LocalDate.of(2025, 12, 15)
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns listOf()

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.historyStart).isEqualTo(LocalDate.of(2025, 12, 1))
        assertThat(plan.queries()).hasSize(11)
        assertThat(plan.queries()[1].intakeEnd).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(plan.queries()[2].intakeStart).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(plan.queries()[10].intakeEnd).isEqualTo(plan.end)
    }

    @Test
    fun `오래된 성공 실행과 최근 실패 실행이 있을 때__계획하면__성공한 실행의 종료일부터 재개한다`() {
        // given
        val failed = execution(4L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true)
        val unknown = execution(3L, BatchStatus.UNKNOWN, LocalDate.of(2026, 9, 8), true)
        val success = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true)

        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns
            listOf(failed.jobInstance, unknown.jobInstance, success.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(failed.jobInstance) } returns listOf(failed)
        every { explorer.getJobExecutions(unknown.jobInstance) } returns listOf(unknown)
        every { explorer.getJobExecutions(success.jobInstance) } returns listOf(success)

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 7, 1))
    }

    @Test
    fun `예전 방식의 성공만 있을 때__계획하면__그것을 수정 수집 체크포인트로 보지 않는다`() {
        // given
        val legacy = execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 1, 1), false)

        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns listOf(legacy.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(legacy.jobInstance) } returns listOf(legacy)

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    @Test
    fun `최근에 성공했을 때__계획하면__제공자 지연에 대비해 30일 겹침을 유지한다`() {
        // given
        val recent = execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true)

        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns listOf(recent.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(recent.jobInstance) } returns listOf(recent)

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    @Test
    fun `성공한 구간이 미래일 때__계획하면__갱신을 건너뛰는 대신 실패한다`() {
        // given
        val future = execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 11), true)

        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns listOf(future.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(future.jobInstance) } returns listOf(future)

        // when
        val thrown = assertThatThrownBy { factory.create("apmsAnimalSyncJob") }

        // then
        thrown.hasMessageContaining("future")
    }

    @Test
    fun `메타데이터를 읽을 수 없을 때__계획하면__최초 실행으로 가정하지 않고 실패한다`() {
        // given
        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } throws IllegalStateException("metadata unavailable")

        // when
        val thrown = assertThatThrownBy { factory.create("apmsAnimalSyncJob") }

        // then
        thrown.hasMessageContaining("metadata unavailable")
    }

    @Test
    fun `성공이 없고 첫 시도가 30일보다 오래됐을 때__계획하면__원래 구간을 유지한다`() {
        // given
        val latestFailure = execution(2L, BatchStatus.FAILED, LocalDate.of(2026, 9, 9), true)
        val firstFailure = execution(1L, BatchStatus.FAILED, LocalDate.of(2026, 7, 1), true)

        every { animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns
            listOf(latestFailure.jobInstance, firstFailure.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(latestFailure.jobInstance) } returns listOf(latestFailure)
        every { explorer.getJobExecutions(firstFailure.jobInstance) } returns listOf(firstFailure)

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(plan.historyStart).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `건너뛴 항목이 있는 완료 실행이 있을 때__계획하면__이전 성공 시점을 넘겨 당기지 않는다`() {
        // given
        val skipped = execution(2L, BatchStatus.COMPLETED, LocalDate.of(2026, 9, 9), true)
        skipped.createStepExecution("ingest").setReadSkipCount(1)
        val previous = execution(1L, BatchStatus.COMPLETED, LocalDate.of(2026, 7, 1), true)

        every { animals.findEarliestHappenDate(any()) } returns null
        every { explorer.getJobInstances("apmsAnimalSyncJob", 0, 100) } returns
            listOf(skipped.jobInstance, previous.jobInstance)
        every { explorer.getJobInstances("apmsAnimalSyncJob", neq(0), 100) } returns listOf()
        every { explorer.getJobExecutions(skipped.jobInstance) } returns listOf(skipped)
        every { explorer.getJobExecutions(previous.jobInstance) } returns listOf(previous)

        // when
        val plan = factory.create("apmsAnimalSyncJob")

        // then
        assertThat(plan.updatedStart).isEqualTo(LocalDate.of(2026, 7, 1))
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
}
