package com.pawbridge.animalservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Batch 작업 실행을 위한 REST API 컨트롤러
 * - 개발/테스트 환경에서 수동 배치 실행용
 * - 운영 환경에서는 스케줄러로 대체 권장
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final JobLauncher jobLauncher;
    private final Job apmsAnimalSyncJob;

    /**
     * APMS 동물 동기화 배치 수동 실행
     *
     * @return 배치 실행 결과 정보
     */
    @PostMapping("/apms/sync")
    public ResponseEntity<Map<String, Object>> syncApmsAnimals() {
        Map<String, Object> response = new HashMap<>();

        try {
            // JobParameters 생성 (동일 Job 재실행을 위해 timestamp 추가)
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            log.info("APMS 동물 동기화 배치 시작 - JobParameters: {}", jobParameters);

            // Batch Job 실행
            JobExecution jobExecution = jobLauncher.run(apmsAnimalSyncJob, jobParameters);

            log.info("APMS 동물 동기화 배치 완료 - Status: {}, ExitCode: {}",
                    jobExecution.getStatus(),
                    jobExecution.getExitStatus().getExitCode());

            // 응답 생성
            response.put("success", true);
            response.put("jobExecutionId", jobExecution.getId());
            response.put("status", jobExecution.getStatus().toString());
            response.put("exitCode", jobExecution.getExitStatus().getExitCode());
            response.put("startTime", jobExecution.getStartTime());
            response.put("endTime", jobExecution.getEndTime());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("APMS 동물 동기화 배치 실행 중 오류 발생", e);

            response.put("success", false);
            response.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }
}
