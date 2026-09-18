package com.pawbridge.animalservice.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsBatchExecutionRecoveryTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T01:00:00Z"), KST);

    @Mock private JobExplorer explorer;
    @Mock private JobRepository repository;
    private ApmsBatchExecutionRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new ApmsBatchExecutionRecovery(explorer, repository, CLOCK, new ApmsBatchProperties());
    }

    @Test
    void givenOldRunningExecutionAndOwnedProcessLock__whenRecover__thenFailRunningStepAndJob() {
        JobExecution execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30));
        StepExecution step = execution.createStepExecution("elasticsearchIndexStep");
        step.setStatus(BatchStatus.STARTED);
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of(execution));

        assertThat(recovery.recoverOrReject("apmsAnimalSyncJob")).isEqualTo(1);

        assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step.getEndTime()).isEqualTo(LocalDateTime.of(2026, 9, 17, 10, 0));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).contains("named lock was released");
        verify(repository).update(step);
        verify(repository).update(execution);
    }

    @Test
    void givenRecentRunningExecution__whenRecover__thenRejectWithoutRewritingMetadata() {
        JobExecution execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 55));
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of(execution));

        assertThatThrownBy(() -> recovery.recoverOrReject("apmsAnimalSyncJob"))
                .isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void givenOldJobTimestampButRecentStepProgress__whenRecover__thenRejectWithoutRewritingMetadata() {
        JobExecution execution = runningExecution(LocalDateTime.of(2026, 9, 17, 9, 30));
        StepExecution step = execution.createStepExecution("apmsAnimalSyncStep");
        step.setStatus(BatchStatus.STARTED);
        step.setLastUpdated(LocalDateTime.of(2026, 9, 17, 9, 55));
        when(explorer.findRunningJobExecutions("apmsAnimalSyncJob")).thenReturn(Set.of(execution));

        assertThatThrownBy(() -> recovery.recoverOrReject("apmsAnimalSyncJob"))
                .isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException.class);

        verifyNoInteractions(repository);
    }

    private JobExecution runningExecution(LocalDateTime lastUpdated) {
        JobExecution execution = new JobExecution(4969L);
        execution.setStatus(BatchStatus.STARTED);
        execution.setStartTime(lastUpdated);
        execution.setLastUpdated(lastUpdated);
        return execution;
    }
}
