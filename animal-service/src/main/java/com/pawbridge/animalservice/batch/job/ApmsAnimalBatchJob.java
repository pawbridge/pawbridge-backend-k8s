package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * APMS API 동기화 Batch Job 설정
 * - APMS API로부터 유기동물 데이터를 조회하여 DB에 저장
 * - 저장 완료 후 Elasticsearch에 자동 인덱싱
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
    private final ElasticsearchIndexService elasticsearchIndexService;

    private static final int CHUNK_SIZE = 500; // Reader의 PAGE_SIZE와 동일하게 설정 (메모리 효율)

    /**
     * APMS 동물 동기화 Job
     * - Step 1: APMS API → MySQL 저장
     * - Step 2: MySQL → Elasticsearch 인덱싱
     */
    @Bean
    public Job apmsAnimalSyncJob() {
        return new JobBuilder("apmsAnimalSyncJob", jobRepository)
                .start(apmsAnimalSyncStep())
                .next(elasticsearchIndexStep())
                .build();
    }

    /**
     * APMS 동물 동기화 Step
     * - Reader: APMS API 호출
     * - Processor: DTO → Entity 변환
     * - Writer: DB 저장
     * - FaultTolerant: 에러 발생 시 Skip/Retry 정책
     */
    @Bean
    public Step apmsAnimalSyncStep() {
        return new StepBuilder("apmsAnimalSyncStep", jobRepository)
                .<ApmsAnimal, Animal>chunk(CHUNK_SIZE, transactionManager)
                .reader(apmsItemReader)
                .processor(animalItemProcessor)
                .writer(animalItemWriter)
                .faultTolerant() // FaultTolerant 설정
                .skipLimit(100) // Skip 정책: 최대 100개 아이템까지 스킵 허용
                .skip(FeignException.class) // API 호출 실패 시 해당 아이템 스킵 (예: 401, 500 등)
                .skip(IllegalArgumentException.class) // 데이터 파싱/변환 오류 시 스킵
                .skip(DataAccessException.class) // DB 저장 오류 시 스킵 (예: 제약조건 위반)
                .skip(Exception.class) // 일반 예외도 스킵 (NPE 등)
                .build();
    }

    /**
     * Elasticsearch 인덱싱 Step
     * - MySQL에 저장된 전체 동물 데이터를 Elasticsearch에 인덱싱
     * - 기존 ES 데이터 삭제 후 전체 재인덱싱 (데이터 일관성 보장)
     */
    @Bean
    public Step elasticsearchIndexStep() {
        return new StepBuilder("elasticsearchIndexStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    log.info("[BATCH] Elasticsearch 인덱싱 Step 시작");
                    long indexedCount = elasticsearchIndexService.reindexAllAnimals();
                    log.info("[BATCH] Elasticsearch 인덱싱 Step 완료: {} 건", indexedCount);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}

