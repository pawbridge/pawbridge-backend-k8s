package com.pawbridge.backend.animal.internal.apms.web

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import java.time.LocalDateTime

class ApmsBatchControllerTest {
    private val runner: ApmsBatchRunner = mock(ApmsBatchRunner::class.java)
    private val controller = ApmsBatchController(runner)

    @Test
    fun givenCompletedRunWithoutSkips__whenSynced__thenOriginalOkBodyIncludesCollectionCounts() {
        val execution = execution(BatchStatus.COMPLETED)
        execution.setStartTime(LocalDateTime.of(2026, 10, 22, 9, 0, 1))
        execution.setEndTime(LocalDateTime.of(2026, 10, 22, 9, 3, 2))
        execution.createStepExecution("apmsAnimalSyncStep")
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 1)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 42)
        `when`(runner.run()).thenReturn(execution)

        val response = controller.syncApmsAnimals()

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).containsEntry("jobExecutionId", 5L).containsEntry("status", "COMPLETED")
            .containsEntry("startTime", "2026-10-22 09:00:01").containsEntry("endTime", "2026-10-22 09:03:02")
            .containsEntry("skipCount", 0L).containsEntry("collectionStatus", "COMPLETE")
            .containsEntry("incompleteQueryCount", 0).containsEntry("countMismatchQueryCount", 1)
            .containsEntry("collectedCount", 42).containsEntry("searchSyncStatus", "NOT_STARTED")
    }

    @Test
    fun givenIncompleteSnapshotAndOriginalSearchStep__whenSynced__thenReportIncompleteAndStepStatus() {
        val execution = execution(BatchStatus.COMPLETED)
        execution.createStepExecution("elasticsearchIndexStep").status = BatchStatus.FAILED
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 3)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 7)
        `when`(runner.run()).thenReturn(execution)

        val body = controller.syncApmsAnimals().body

        assertThat(body).containsEntry("collectionStatus", "INCOMPLETE").containsEntry("incompleteQueryCount", 3)
            .containsEntry("countMismatchQueryCount", 0).containsEntry("searchSyncStatus", "FAILED")
            .containsEntry("startTime", null).containsEntry("endTime", null)
    }

    @Test
    fun givenNoSnapshotCounts__whenSynced__thenCollectionKeysAreAbsent() {
        `when`(runner.run()).thenReturn(execution(BatchStatus.COMPLETED))

        assertThat(controller.syncApmsAnimals().body)
            .doesNotContainKeys("collectionStatus", "incompleteQueryCount", "countMismatchQueryCount", "collectedCount")
    }

    @Test
    fun givenSkipsOrFailure__whenSynced__thenInternalServerError() {
        val skipped = execution(BatchStatus.COMPLETED)
        skipped.createStepExecution("apmsAnimalSyncStep").setReadSkipCount(2)
        `when`(runner.run()).thenReturn(skipped, execution(BatchStatus.FAILED))

        val first = controller.syncApmsAnimals()

        assertThat(first.statusCode.value()).isEqualTo(500)
        assertThat(first.body).containsEntry("skipCount", 2L)
        assertThat(controller.syncApmsAnimals().statusCode.value()).isEqualTo(500)
    }

    @Test
    fun givenRunningOrUnknownExecution__whenSynced__thenServiceUnavailable() {
        `when`(runner.run()).thenReturn(execution(BatchStatus.STARTED), execution(BatchStatus.UNKNOWN))

        assertThat(controller.syncApmsAnimals().statusCode.value()).isEqualTo(503)
        assertThat(controller.syncApmsAnimals().statusCode.value()).isEqualTo(503)
    }

    @Test
    fun givenRunnerGuards__whenSynced__thenOriginalConflictAndUnavailableBodies() {
        `when`(runner.run()).thenThrow(ApmsBatchRunner.AlreadyRunningException())
            .thenThrow(ApmsBatchRunner.UnavailableException())

        val busy = controller.syncApmsAnimals()

        assertThat(busy.statusCode.value()).isEqualTo(409)
        assertThat(busy.body).isEqualTo(mapOf("status" to "ALREADY_RUNNING"))
        val guard = controller.syncApmsAnimals()
        assertThat(guard.statusCode.value()).isEqualTo(503)
        assertThat(guard.body).isEqualTo(mapOf("status" to "GUARD_UNAVAILABLE"))
    }

    @Test
    fun givenUnexpectedFailure__whenSynced__thenFixedFailureBodyWithoutExceptionMessage() {
        `when`(runner.run()).thenThrow(IllegalStateException("secret detail"))

        val response = controller.syncApmsAnimals()

        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body).isEqualTo(mapOf("status" to "FAILED", "error" to "APMS batch execution failed"))
    }

    private fun execution(status: BatchStatus): JobExecution {
        val execution = JobExecution(JobInstance(1L, "apmsAnimalSyncJob"), 5L, JobParameters())
        execution.status = status
        return execution
    }
}
