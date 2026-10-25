package com.pawbridge.backend.animal.internal.apms.web

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

/**
 * Original animal-service manual APMS trigger. Synchronous: the request holds until the job ends.
 * Root must apply authorization before registering this handler.
 */
@RestController
@RequestMapping("/api/v1/batch")
class ApmsBatchController(
    private val apmsBatchRunner: ApmsBatchRunner,
) {

    @PostMapping("/apms/sync")
    fun syncApmsAnimals(): ResponseEntity<MutableMap<String, Any?>> {
        val response = HashMap<String, Any?>()

        try {
            val jobExecution = apmsBatchRunner.run()

            log.info(
                "APMS 동물 동기화 배치 완료 - Status: {}, ExitCode: {}",
                jobExecution.status,
                jobExecution.exitStatus.exitCode,
            )

            response["jobExecutionId"] = jobExecution.id
            response["status"] = jobExecution.status.toString()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            response["startTime"] = jobExecution.startTime?.format(formatter)
            response["endTime"] = jobExecution.endTime?.format(formatter)

            val skipCount = jobExecution.stepExecutions.sumOf { step -> step.skipCount }
            response["skipCount"] = skipCount
            val collection = jobExecution.executionContext
            if (collection.containsKey(ApmsAnimalSnapshot.INCOMPLETE_COUNT)) {
                val incomplete = collection.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)
                response["collectionStatus"] = if (incomplete == 0) "COMPLETE" else "INCOMPLETE"
                response["incompleteQueryCount"] = incomplete
                response["countMismatchQueryCount"] = collection.getInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 0)
                response["collectedCount"] = collection.getInt(ApmsAnimalSnapshot.COLLECTED_COUNT)
            }
            // Original step name; the PostgreSQL job has no such step, so this stays NOT_STARTED as in the original.
            response["searchSyncStatus"] = jobExecution.stepExecutions
                .filter { step -> "elasticsearchIndexStep" == step.stepName }
                .map { step -> step.status.toString() }
                .firstOrNull() ?: "NOT_STARTED"
            if (jobExecution.status == BatchStatus.COMPLETED && skipCount == 0L) {
                return ResponseEntity.ok(response)
            }
            if (jobExecution.isRunning || jobExecution.status == BatchStatus.UNKNOWN) {
                return ResponseEntity.status(503).body(response)
            }
            return ResponseEntity.internalServerError().body(response)
        } catch (exception: ApmsBatchRunner.AlreadyRunningException) {
            response["status"] = "ALREADY_RUNNING"
            return ResponseEntity.status(409).body(response)
        } catch (exception: ApmsBatchRunner.UnavailableException) {
            response["status"] = "GUARD_UNAVAILABLE"
            return ResponseEntity.status(503).body(response)
        } catch (e: Exception) {
            log.error("APMS 동물 동기화 배치 실행 실패 - 예외 종류: {}", e.javaClass.simpleName)

            response["status"] = "FAILED"
            response["error"] = "APMS batch execution failed"

            return ResponseEntity.internalServerError().body(response)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ApmsBatchController::class.java)
    }
}
