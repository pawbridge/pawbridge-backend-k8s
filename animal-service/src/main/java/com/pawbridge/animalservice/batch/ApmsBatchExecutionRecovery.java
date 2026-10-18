package com.pawbridge.animalservice.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsBatchExecutionRecovery {
    private static final String RECOVERY_DESCRIPTION =
            "Execution process disappeared and the APMS named lock was released";

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final Clock apmsClock;
    private final ApmsBatchProperties properties;

    /**
     * Called only while the caller owns the database session lock shared by APMS and shelter collection.
     * A recently updated execution is rejected instead of being rewritten; an old
     * running execution is a process orphan and is closed so a new full upsert can converge.
     */
    @Transactional
    public int recoverOrReject(String jobName) {
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
