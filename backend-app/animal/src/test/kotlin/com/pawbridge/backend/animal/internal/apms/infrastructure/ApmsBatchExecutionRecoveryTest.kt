package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class ApmsBatchExecutionRecoveryTest {
    @Mock
    private lateinit var explorer: JobExplorer

    @Mock
    private lateinit var repository: JobRepository

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
    fun givenOldRunningExecutionAndOwnedProcessLock__whenRecover__thenFailRunningStepAndJob() {
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30))
        val step = execution.createStepExecution("elasticsearchIndexStep")
        step.status = BatchStatus.STARTED
        `when`(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(setOf(execution))

        assertThat(recovery.recoverOrReject("apmsAnimalSyncJob")).isEqualTo(1)

        assertThat(step.status).isEqualTo(BatchStatus.FAILED)
        assertThat(step.endTime).isEqualTo(LocalDateTime.of(2026, 9, 17, 10, 0))
        assertThat(execution.status).isEqualTo(BatchStatus.FAILED)
        assertThat(execution.exitStatus.exitDescription).contains("named lock was released")
        verify(repository).update(step)
        verify(repository).update(execution)
    }

    @Test
    fun givenRecentRunningExecution__whenRecover__thenRejectWithoutRewritingMetadata() {
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 55))
        `when`(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(setOf(execution))

        assertThatThrownBy { recovery.recoverOrReject("apmsAnimalSyncJob") }
            .isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException::class.java)
        verifyNoInteractions(repository)
    }

    @Test
    fun givenOldJobTimestampButRecentStepProgress__whenRecover__thenRejectWithoutRewritingMetadata() {
        val execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30))
        val step = execution.createStepExecution("apmsAnimalSyncStep")
        step.status = BatchStatus.STARTED
        step.setLastUpdated(LocalDateTime.of(2026, 9, 17, 9, 55))
        `when`(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(setOf(execution))

        assertThatThrownBy { recovery.recoverOrReject("apmsAnimalSyncJob") }
            .isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException::class.java)
        verifyNoInteractions(repository)
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
