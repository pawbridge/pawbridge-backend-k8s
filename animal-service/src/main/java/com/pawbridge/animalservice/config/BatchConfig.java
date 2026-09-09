package com.pawbridge.animalservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Spring Batch 설정
 *
 * Spring Boot 3.x에서는 @EnableBatchProcessing 불필요
 * - spring-boot-starter-batch 의존성만으로 자동 설정됨
 * - JobRepository, JobLauncher 등이 자동으로 빈 등록됨
 *
 * Job과 Step은 batch 패키지에서 정의:
 * - batch/job/ApmsAnimalBatchJob.java
 * - batch/reader/ApmsItemReader.java
 * - batch/processor/AnimalItemProcessor.java
 * - batch/writer/AnimalItemWriter.java
 */
@Configuration
public class BatchConfig {
    // Boot may select batchTaskExecutor for its default launcher. APMS must instead
    // finish on the caller thread so its database session lock covers the whole job.
    @Bean
    public TaskExecutorJobLauncher apmsJobLauncher(JobRepository jobRepository) throws Exception {
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }
}
