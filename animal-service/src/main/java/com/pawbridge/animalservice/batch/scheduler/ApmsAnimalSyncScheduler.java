package com.pawbridge.animalservice.batch.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * APMS 동물 데이터 동기화 스케줄러
 * - 매일 정해진 시간에 APMS API로부터 유기동물 데이터를 동기화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsAnimalSyncScheduler {

    private final JobLauncher jobLauncher;
    private final Job apmsAnimalSyncJob;

    /**
     * APMS 동물 데이터 동기화 스케줄 실행
     * - Cron: 매일 새벽 2시 실행 (0 0 2 * * ?)
     * - JobParameters에 timestamp를 추가하여 매번 새로운 Job Instance 생성
     */
    @Scheduled(cron = "${batch.apms.sync.cron:0 0 2 * * ?}")
    public void syncApmsAnimalData() {
        try {
            log.info("APMS 동물 데이터 동기화 스케줄 시작");

            // JobParameters에 현재 시간 추가 (매번 새로운 Job Instance 생성)
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            // Job 실행
            JobExecution jobExecution = jobLauncher.run(apmsAnimalSyncJob, jobParameters);

            log.info("APMS 동물 데이터 동기화 스케줄 완료 - Status: {}, ExitCode: {}",
                    jobExecution.getStatus(), jobExecution.getExitStatus().getExitCode());

        } catch (Exception e) {
            log.error("APMS 동물 데이터 동기화 스케줄 실행 중 오류 발생", e);
        }
    }
}
