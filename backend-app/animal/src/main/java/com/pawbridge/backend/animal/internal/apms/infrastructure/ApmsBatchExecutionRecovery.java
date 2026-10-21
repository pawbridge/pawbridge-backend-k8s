package com.pawbridge.backend.animal.internal.apms.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
public class ApmsBatchExecutionRecovery {
    private static final String RECOVERY_DESCRIPTION =
            "Execution process disappeared and the APMS named lock was released";

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final Clock apmsClock;
    private final ApmsBatchProperties properties;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public ApmsBatchExecutionRecovery(JobExplorer jobExplorer, JobRepository jobRepository, Clock apmsClock,
            ApmsBatchProperties properties, org.springframework.transaction.PlatformTransactionManager manager,
            int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("APMS recovery timeout must be positive");
        this.jobExplorer = jobExplorer; this.jobRepository = jobRepository; this.apmsClock = apmsClock; this.properties = properties;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.transactions.setTimeout(timeoutSeconds);
    }

    /**
     * Called only while the caller owns the database session lock shared by APMS and shelter collection.
     * A recently updated execution is rejected instead of being rewritten; an old
     * running execution is a process orphan and is closed so a new full upsert can converge.
     */
    public int recoverOrReject(String jobName) {
        return transactions.execute(status -> recover(jobName));
    }

    private int recover(String jobName) {
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        if (running.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(apmsClock);
        LocalDateTime cutoff = now.minus(properties.getStaleExecutionThreshold());
        for (JobExecution execution : running) {
            LocalDateTime heartbeat = latestTimestamp(execution);
            if (heartbeat == null || heartbeat.isAfter(cutoff)) {
                throw new ActiveExecutionMetadataException(execution.getId());
            }
        }

        running.forEach(execution -> failExecution(execution, now));
        return running.size();
    }

    private void failExecution(JobExecution execution, LocalDateTime now) {
        LocalDateTime staleSince = latestTimestamp(execution);
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStatus().isRunning()) {
                step.setStatus(BatchStatus.FAILED);
                step.setExitStatus(new ExitStatus(ExitStatus.FAILED.getExitCode(), RECOVERY_DESCRIPTION));
                step.setEndTime(now);
                step.setLastUpdated(now);
                jobRepository.update(step);
            }
        }

        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(new ExitStatus(ExitStatus.FAILED.getExitCode(), RECOVERY_DESCRIPTION));
        execution.setEndTime(now);
        execution.setLastUpdated(now);
        jobRepository.update(execution);
        log.warn("Recovered orphaned APMS batch execution: executionId={}, staleSince={}",
                execution.getId(), staleSince);
    }

    private LocalDateTime latestTimestamp(JobExecution execution) {
        LocalDateTime latest = execution.getLastUpdated();
        if (latest == null) {
            latest = execution.getStartTime() != null ? execution.getStartTime() : execution.getCreateTime();
        }
        for (StepExecution step : execution.getStepExecutions()) {
            LocalDateTime stepUpdated = step.getLastUpdated();
            if (stepUpdated != null && (latest == null || stepUpdated.isAfter(latest))) {
                latest = stepUpdated;
            }
        }
        return latest;
    }

    public static class ActiveExecutionMetadataException extends RuntimeException {
        public ActiveExecutionMetadataException(Long executionId) {
            super("APMS execution metadata is too recent to recover: " + executionId);
        }
    }
}
