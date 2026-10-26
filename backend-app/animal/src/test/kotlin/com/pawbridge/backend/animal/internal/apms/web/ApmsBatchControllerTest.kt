package com.pawbridge.backend.animal.internal.apms.web

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import java.time.LocalDateTime

class ApmsBatchControllerTest {
    private val runner = mockk<ApmsBatchRunner>()
    private val controller = ApmsBatchController(runner)

    @Test
    fun `건너뜀 없이 완료된 실행일 때__동기화하면__원래의 OK 본문에 수집 건수를 담는다`() {
        // given
        val execution = execution(BatchStatus.COMPLETED)
        execution.setStartTime(LocalDateTime.of(2026, 10, 22, 9, 0, 1))
        execution.setEndTime(LocalDateTime.of(2026, 10, 22, 9, 3, 2))
        execution.createStepExecution("apmsAnimalSyncStep")
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 1)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 42)

        every { runner.run() } returns execution

        // when
        val response = controller.syncApmsAnimals()

        // then
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).containsEntry("jobExecutionId", 5L).containsEntry("status", "COMPLETED")
            .containsEntry("startTime", "2026-10-22 09:00:01").containsEntry("endTime", "2026-10-22 09:03:02")
            .containsEntry("skipCount", 0L).containsEntry("collectionStatus", "COMPLETE")
            .containsEntry("incompleteQueryCount", 0).containsEntry("countMismatchQueryCount", 1)
            .containsEntry("collectedCount", 42).containsEntry("searchSyncStatus", "NOT_STARTED")
    }

    @Test
    fun `불완전한 스냅샷과 원래의 검색 단계가 있을 때__동기화하면__불완전 상태와 단계 상태를 보고한다`() {
        // given
        val execution = execution(BatchStatus.COMPLETED)
        execution.createStepExecution("elasticsearchIndexStep").status = BatchStatus.FAILED
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 3)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COLLECTED_COUNT, 7)

        every { runner.run() } returns execution

        // when
        val body = controller.syncApmsAnimals().body

        // then
        assertThat(body).containsEntry("collectionStatus", "INCOMPLETE").containsEntry("incompleteQueryCount", 3)
            .containsEntry("countMismatchQueryCount", 0).containsEntry("searchSyncStatus", "FAILED")
            .containsEntry("startTime", null).containsEntry("endTime", null)
    }

    @Test
    fun `스냅샷 건수가 없을 때__동기화하면__수집 관련 키를 싣지 않는다`() {
        // given
        every { runner.run() } returns execution(BatchStatus.COMPLETED)

        // when
        val body = controller.syncApmsAnimals().body

        // then
        assertThat(body)
            .doesNotContainKeys("collectionStatus", "incompleteQueryCount", "countMismatchQueryCount", "collectedCount")
    }

    @Test
    fun `건너뜀이 있거나 실패한 실행일 때__동기화하면__내부 서버 오류를 돌려준다`() {
        // given
        val skipped = execution(BatchStatus.COMPLETED)
        skipped.createStepExecution("apmsAnimalSyncStep").setReadSkipCount(2)

        every { runner.run() } returnsMany listOf(skipped, execution(BatchStatus.FAILED))

        // when
        val first = controller.syncApmsAnimals()
        val second = controller.syncApmsAnimals()

        // then
        assertThat(first.statusCode.value()).isEqualTo(500)
        assertThat(first.body).containsEntry("skipCount", 2L)
        assertThat(second.statusCode.value()).isEqualTo(500)
    }

    @Test
    fun `실행 중이거나 상태를 알 수 없는 실행일 때__동기화하면__서비스 이용 불가를 돌려준다`() {
        // given
        every { runner.run() } returnsMany listOf(execution(BatchStatus.STARTED), execution(BatchStatus.UNKNOWN))

        // when
        val running = controller.syncApmsAnimals()
        val unknown = controller.syncApmsAnimals()

        // then
        assertThat(running.statusCode.value()).isEqualTo(503)
        assertThat(unknown.statusCode.value()).isEqualTo(503)
    }

    @Test
    fun `실행기의 보호 장치가 작동할 때__동기화하면__원래의 충돌 본문과 이용 불가 본문을 돌려준다`() {
        // given
        every { runner.run() } throws ApmsBatchRunner.AlreadyRunningException() andThenThrows
            ApmsBatchRunner.UnavailableException()

        // when
        val busy = controller.syncApmsAnimals()
        val guard = controller.syncApmsAnimals()

        // then
        assertThat(busy.statusCode.value()).isEqualTo(409)
        assertThat(busy.body).isEqualTo(mapOf("status" to "ALREADY_RUNNING"))
        assertThat(guard.statusCode.value()).isEqualTo(503)
        assertThat(guard.body).isEqualTo(mapOf("status" to "GUARD_UNAVAILABLE"))
    }

    @Test
    fun `예기치 않은 실패가 날 때__동기화하면__예외 메시지 없이 고정된 실패 본문을 돌려준다`() {
        // given
        every { runner.run() } throws IllegalStateException("secret detail")

        // when
        val response = controller.syncApmsAnimals()

        // then
        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body).isEqualTo(mapOf("status" to "FAILED", "error" to "APMS batch execution failed"))
    }

    private fun execution(status: BatchStatus): JobExecution {
        val execution = JobExecution(JobInstance(1L, "apmsAnimalSyncJob"), 5L, JobParameters())
        execution.status = status
        return execution
    }
}
