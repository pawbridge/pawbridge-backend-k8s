package com.pawbridge.backend.animal.internal.apms.web;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot;
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApmsBatchControllerTest {
    private final ApmsBatchRunner runner = mock(ApmsBatchRunner.class);
    private final ApmsBatchController controller = new ApmsBatchController(runner);

    @Test
    void givenCompletedRunWithoutSkips__whenSynced__thenOriginalOkBodyIncludesCollectionCounts() throws Exception {
        var execution = execution(BatchStatus.COMPLETED);
        execution.setStartTime(LocalDateTime.of(2026, 10, 22, 9, 0, 1));
        execution.setEndTime(LocalDateTime.of(2026, 10, 22, 9, 3, 2));
        execution.createStepExecution("apmsAnimalSyncStep");
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 1);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 42);
        when(runner.run()).thenReturn(execution);

        var response = controller.syncApmsAnimals();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("jobExecutionId", 5L).containsEntry("status", "COMPLETED")
                .containsEntry("startTime", "2026-10-22 09:00:01").containsEntry("endTime", "2026-10-22 09:03:02")
                .containsEntry("skipCount", 0L).containsEntry("collectionStatus", "COMPLETE")
                .containsEntry("incompleteQueryCount", 0).containsEntry("countMismatchQueryCount", 1)
                .containsEntry("collectedCount", 42).containsEntry("searchSyncStatus", "NOT_STARTED");
    }

    @Test
    void givenIncompleteSnapshotAndOriginalSearchStep__whenSynced__thenReportIncompleteAndStepStatus() throws Exception {
        var execution = execution(BatchStatus.COMPLETED);
        execution.createStepExecution("elasticsearchIndexStep").setStatus(BatchStatus.FAILED);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 3);
        execution.getExecutionContext().putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 7);
        when(runner.run()).thenReturn(execution);

        var body = controller.syncApmsAnimals().getBody();

        assertThat(body).containsEntry("collectionStatus", "INCOMPLETE").containsEntry("incompleteQueryCount", 3)
                .containsEntry("countMismatchQueryCount", 0).containsEntry("searchSyncStatus", "FAILED")
                .containsEntry("startTime", null).containsEntry("endTime", null);
    }

    @Test
    void givenNoSnapshotCounts__whenSynced__thenCollectionKeysAreAbsent() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.COMPLETED));

        assertThat(controller.syncApmsAnimals().getBody())
                .doesNotContainKeys("collectionStatus", "incompleteQueryCount", "countMismatchQueryCount", "collectedCount");
    }

    @Test
    void givenSkipsOrFailure__whenSynced__thenInternalServerError() throws Exception {
        var skipped = execution(BatchStatus.COMPLETED);
        skipped.createStepExecution("apmsAnimalSyncStep").setReadSkipCount(2);
        when(runner.run()).thenReturn(skipped, execution(BatchStatus.FAILED));

        var first = controller.syncApmsAnimals();
        assertThat(first.getStatusCode().value()).isEqualTo(500);
        assertThat(first.getBody()).containsEntry("skipCount", 2L);
        assertThat(controller.syncApmsAnimals().getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void givenRunningOrUnknownExecution__whenSynced__thenServiceUnavailable() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.STARTED), execution(BatchStatus.UNKNOWN));

        assertThat(controller.syncApmsAnimals().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.syncApmsAnimals().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void givenRunnerGuards__whenSynced__thenOriginalConflictAndUnavailableBodies() throws Exception {
        when(runner.run()).thenThrow(new ApmsBatchRunner.AlreadyRunningException())
                .thenThrow(new ApmsBatchRunner.UnavailableException());

        var busy = controller.syncApmsAnimals();
        assertThat(busy.getStatusCode().value()).isEqualTo(409);
        assertThat(busy.getBody()).isEqualTo(Map.of("status", "ALREADY_RUNNING"));
        var guard = controller.syncApmsAnimals();
        assertThat(guard.getStatusCode().value()).isEqualTo(503);
        assertThat(guard.getBody()).isEqualTo(Map.of("status", "GUARD_UNAVAILABLE"));
    }

    @Test
    void givenUnexpectedFailure__whenSynced__thenFixedFailureBodyWithoutExceptionMessage() throws Exception {
        when(runner.run()).thenThrow(new IllegalStateException("secret detail"));

        var response = controller.syncApmsAnimals();

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "FAILED", "error", "APMS batch execution failed"));
    }

    private static JobExecution execution(BatchStatus status) {
        var execution = new JobExecution(new JobInstance(1L, "apmsAnimalSyncJob"), 5L, new JobParameters());
        execution.setStatus(status);
        return execution;
    }
}
