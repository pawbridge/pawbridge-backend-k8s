package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime

class ApmsBatchExecutionRecovery(
    private val jobExplorer: JobExplorer,
    private val jobRepository: JobRepository,
    private val apmsClock: Clock,
    private val properties: ApmsBatchProperties,
    manager: PlatformTransactionManager,
    timeoutSeconds: Int,
) {
    private val transactions: TransactionTemplate

    init {
        if (timeoutSeconds < 1) {
            throw IllegalArgumentException("APMS recovery timeout must be positive")
        }
        transactions = TransactionTemplate(manager)
        transactions.timeout = timeoutSeconds
    }

    /**
     * Called only while the caller owns the database session lock shared by APMS and shelter collection.
     * A recently updated execution is rejected instead of being rewritten; an old
     * running execution is a process orphan and is closed so a new full upsert can converge.
     */
    fun recoverOrReject(jobName: String): Int {
        return transactions.execute { recover(jobName) }!!
    }

    private fun recover(jobName: String): Int {
        val running = jobExplorer.findRunningJobExecutions(jobName)
        if (running.isEmpty()) {
            return 0
        }

        val now = LocalDateTime.now(apmsClock)
        val cutoff = now.minus(properties.getStaleExecutionThreshold())
        for (execution in running) {
            val heartbeat = latestTimestamp(execution)
            if (heartbeat == null || heartbeat.isAfter(cutoff)) {
                throw ActiveExecutionMetadataException(execution.id)
            }
        }

        for (execution in running) {
            failExecution(execution, now)
        }
        return running.size
    }

    private fun failExecution(execution: JobExecution, now: LocalDateTime) {
        val staleSince = latestTimestamp(execution)
        for (step in execution.stepExecutions) {
            if (step.status.isRunning) {
                step.setStatus(BatchStatus.FAILED)
                step.setExitStatus(ExitStatus(ExitStatus.FAILED.exitCode, RECOVERY_DESCRIPTION))
                step.setEndTime(now)
                step.setLastUpdated(now)
                jobRepository.update(step)
            }
        }

        execution.setStatus(BatchStatus.FAILED)
        execution.setExitStatus(ExitStatus(ExitStatus.FAILED.exitCode, RECOVERY_DESCRIPTION))
        execution.setEndTime(now)
        execution.setLastUpdated(now)
        jobRepository.update(execution)
        log.warn(
            "Recovered orphaned APMS batch execution: executionId={}, staleSince={}",
            execution.id,
            staleSince,
        )
    }

    private fun latestTimestamp(execution: JobExecution): LocalDateTime? {
        var latest: LocalDateTime? = execution.lastUpdated
        if (latest == null) {
            latest = if (execution.startTime != null) execution.startTime else execution.createTime
        }
        for (step in execution.stepExecutions) {
            val stepUpdated: LocalDateTime? = step.lastUpdated
            if (stepUpdated != null && (latest == null || stepUpdated.isAfter(latest))) {
                latest = stepUpdated
            }
        }
        return latest
    }

    class ActiveExecutionMetadataException(executionId: Long?) :
        RuntimeException("APMS execution metadata is too recent to recover: $executionId")

    companion object {
        private val log = LoggerFactory.getLogger(ApmsBatchExecutionRecovery::class.java)

        private const val RECOVERY_DESCRIPTION =
            "Execution process disappeared and the APMS named lock was released"
    }
}
