package com.pawbridge.backend.animal.internal.apms.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ApmsBatchExecutionRecoveryTest {
    private val explorer = mockk<JobExplorer>()
    private val repository = mockk<JobRepository>()

    private lateinit var recovery: ApmsBatchExecutionRecovery

    @BeforeEach
    fun setUp() {
        recovery = ApmsBatchExecutionRecovery(
            explorer,
            repository,
            CLOCK,
            ApmsBatchProperties(),
            ResourcelessTransactionManager(),
            15,
        )
    }

    @Test
    fun `오래된 실행이 있고 프로세스 락을 소유했을 때__복구하면__실행 중인 단계와 작업을 실패 처리한다`() {
        // given
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30))
        val step = execution.createStepExecution("elasticsearchIndexStep")
        step.status = BatchStatus.STARTED

        every { explorer.findRunningJobExecutions(any()) } returns setOf(execution)
        justRun { repository.update(any<StepExecution>()) }
        justRun { repository.update(any<JobExecution>()) }

        // when
        val recovered = recovery.recoverOrReject("apmsAnimalSyncJob")

        // then
        assertThat(recovered).isEqualTo(1)
        assertThat(step.status).isEqualTo(BatchStatus.FAILED)
        assertThat(step.endTime).isEqualTo(LocalDateTime.of(2026, 9, 17, 10, 0))
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(execution.exitStatus.exitDescription).contains("named lock was released")
        verify(exactly = 1) { explorer.findRunningJobExecutions("apmsAnimalSyncJob") }
        verify(exactly = 1) { repository.update(step) }
        verify(exactly = 1) { repository.update(execution) }
    }

    @Test
    fun `최근에 갱신된 실행이 있을 때__복구하면__메타데이터를 고치지 않고 거부한다`() {
        // given
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 55))

        every { explorer.findRunningJobExecutions(any()) } returns setOf(execution)

        // when
        val thrown = assertThatThrownBy { recovery.recoverOrReject("apmsAnimalSyncJob") }

        // then
        thrown.isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException::class.java)
        confirmVerified(repository)
    }

    @Test
    fun `작업 시각은 오래됐지만 단계 진행이 최근일 때__복구하면__메타데이터를 고치지 않고 거부한다`() {
        // given
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30))
        val step = execution.createStepExecution("apmsAnimalSyncStep")
        step.status = BatchStatus.STARTED
        step.setLastUpdated(LocalDateTime.of(2026, 9, 17, 9, 55))

        every { explorer.findRunningJobExecutions(any()) } returns setOf(execution)

        // when
        val thrown = assertThatThrownBy { recovery.recoverOrReject("apmsAnimalSyncJob") }

        // then
        thrown.isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException::class.java)
        confirmVerified(repository)
    }

    private fun runningExecution(lastUpdated: LocalDateTime): JobExecution {
        val execution = JobExecution(4969L)
        execution.status = BatchStatus.STARTED
        execution.setStartTime(lastUpdated)
        execution.setLastUpdated(lastUpdated)
        return execution
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-17T01:00:00Z"), KST)
    }
}
