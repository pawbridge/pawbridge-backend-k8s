package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * APMS API 동기화 Batch Job 설정
 * - APMS API로부터 유기동물 데이터를 조회하여 DB에 저장
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApmsAnimalBatchJob {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    private final ApmsItemReader apmsItemReader;
    private final AnimalItemProcessor animalItemProcessor;
    private final AnimalItemWriter animalItemWriter;

    private static final int CHUNK_SIZE = 500; // Reader의 PAGE_SIZE와 동일하게 설정 (메모리 효율)

    /**
     * APMS 동물 동기화 Job
     */
    @Bean
    public Job apmsAnimalSyncJob() {
        return new JobBuilder("apmsAnimalSyncJob", jobRepository)
                .start(apmsAnimalSyncStep())
                .build();
    }

    /**
     * APMS 동물 동기화 Step
     * - Reader: APMS API 호출
     * - Processor: DTO → Entity 변환
     * - Writer: DB 저장
     */
    @Bean
    public Step apmsAnimalSyncStep() {
        return new StepBuilder("apmsAnimalSyncStep", jobRepository)
                .<ApmsAnimal, Animal>chunk(CHUNK_SIZE, transactionManager)
                .reader(apmsItemReader)
                .processor(animalItemProcessor)
                .writer(animalItemWriter)
                .build();
    }
}
