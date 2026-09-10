package com.pawbridge.animalservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.pawbridge.animalservice.batch.ApmsBatchRunner;
import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Batch 작업 실행을 위한 REST API 컨트롤러
 * - 내부 CronJob 또는 운영자가 호출하는 동기 실행 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/batch")
@RequiredArgsConstructor
public class BatchController {

    private final ApmsBatchRunner apmsBatchRunner;

    /**
     * APMS 동물 동기화 배치 수동 실행
     *
     * @return 배치 실행 결과 정보
     */
    @PostMapping("/apms/sync")
    public ResponseEntity<Map<String, Object>> syncApmsAnimals() {
        Map<String, Object> response = new HashMap<>();

        try {
            JobExecution jobExecution = apmsBatchRunner.run();

            log.info("APMS 동물 동기화 배치 완료 - Status: {}, ExitCode: {}",
                    jobExecution.getStatus(),
                    jobExecution.getExitStatus().getExitCode());

            // 응답 생성
            response.put("jobExecutionId", jobExecution.getId());
            response.put("status", jobExecution.getStatus().toString());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            response.put("startTime", jobExecution.getStartTime() != null ? jobExecution.getStartTime().format(formatter) : null);
            response.put("endTime", jobExecution.getEndTime() != null ? jobExecution.getEndTime().format(formatter) : null);

            long skipCount = jobExecution.getStepExecutions().stream()
                    .mapToLong(step -> step.getSkipCount()).sum();
            response.put("skipCount", skipCount);
            var collection = jobExecution.getExecutionContext();
            if (collection.containsKey(ApmsAnimalSnapshot.INCOMPLETE_COUNT)) {
                int incomplete = collection.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT);
                response.put("collectionStatus", incomplete == 0 ? "COMPLETE" : "INCOMPLETE");
                response.put("incompleteQueryCount", incomplete);
                response.put("collectedCount", collection.getInt(ApmsAnimalSnapshot.COLLECTED_COUNT));
            }
            response.put("searchSyncStatus", jobExecution.getStepExecutions().stream()
                    .filter(step -> "elasticsearchIndexStep".equals(step.getStepName()))
                    .map(step -> step.getStatus().toString()).findFirst().orElse("NOT_STARTED"));
            if (jobExecution.getStatus() == BatchStatus.COMPLETED && skipCount == 0) {
                return ResponseEntity.ok(response);
            }
            if (jobExecution.isRunning() || jobExecution.getStatus() == BatchStatus.UNKNOWN) {
                return ResponseEntity.status(503).body(response);
            }
            return ResponseEntity.internalServerError().body(response);
        } catch (ApmsBatchRunner.AlreadyRunningException exception) {
            response.put("status", "ALREADY_RUNNING");
            return ResponseEntity.status(409).body(response);
        } catch (ApmsBatchRunner.UnavailableException exception) {
            response.put("status", "GUARD_UNAVAILABLE");
            return ResponseEntity.status(503).body(response);
        } catch (Exception e) {
            log.error("APMS 동물 동기화 배치 실행 실패 - 예외 종류: {}", e.getClass().getSimpleName());

            response.put("status", "FAILED");
            response.put("error", "APMS batch execution failed");

            return ResponseEntity.internalServerError().body(response);
        }
    }
}
