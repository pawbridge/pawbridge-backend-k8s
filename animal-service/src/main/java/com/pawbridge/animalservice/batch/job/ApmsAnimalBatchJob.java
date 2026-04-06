package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.listener.BatchSkipListener;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
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
    private final ShelterPrepTasklet shelterPrepTasklet;
    private final BatchSkipListener batchSkipListener;

    // BatchExecutorConfig에서 정의 — 순환 참조 방지를 위해 분리
    @Autowired
    @Qualifier("batchTaskExecutor")
    private TaskExecutor batchTaskExecutor;

    private static final int CHUNK_SIZE = 1000; // Reader의 PAGE_SIZE와 동일하게 설정 (메모리 효율)

    /**
     * APMS 동물 동기화 Job
     * - Step 0: 보호소 사전 저장 (ShelterPrepTasklet)
     * - Step 1: APMS API → MySQL 저장 (Step 0 성공 시에만 실행)
     * - Step 2: MySQL → Elasticsearch 인덱싱
     *
     * Step Flow: Step 0 FAILED → Job 즉시 종료 (Step 1, 2 실행 안 함)
     */
    @Bean
    public Job apmsAnimalSyncJob() {
        return new JobBuilder("apmsAnimalSyncJob", jobRepository)
                .start(shelterPrepStep())
                    .on("FAILED").fail()  // Job FAILED + 재시작 가능 (.end()는 COMPLETED로 숨김)
                .from(shelterPrepStep())
                    .on("*").to(apmsAnimalSyncStep())
                .from(apmsAnimalSyncStep())
                    .next(elasticsearchIndexStep())
                .end()
                .build();
    }

    /**
     * Step 0: 보호소 사전 저장 Tasklet
     * - APMS API 전체 조회 → 신규 보호소 saveAll()
     * - 실패 시 Step Flow에 의해 Job 종료
     */
    @Bean
    public Step shelterPrepStep() {
        return new StepBuilder("shelterPrepStep", jobRepository)
                .tasklet(shelterPrepTasklet, transactionManager)
                .build();
    }

    /**
     * Step 1: APMS 동물 동기화 Chunk Step
     * - Reader: APMS API 호출 (PAGE_SIZE = 1000)
     * - Processor: DTO → Entity 변환 (shelterCache/existingAnimalIdMap 캐시 조회)
     * - Writer: 신규 saveAll(), 기존 @Modifying UPDATE
     * - FaultTolerant: Skip(최대 100건) + FeignException Retry(최대 3회) + SkipListener 로깅
     */
    @Bean
    public Step apmsAnimalSyncStep() {
        return new StepBuilder("apmsAnimalSyncStep", jobRepository)
                .<ApmsAnimal, Animal>chunk(CHUNK_SIZE, transactionManager)
                .reader(apmsItemReader)
                .processor(animalItemProcessor)
                .writer(animalItemWriter)
                .faultTolerant()
                .skipLimit(100)
                .skip(FeignException.class)
                .skip(IllegalArgumentException.class)
                .skip(DataAccessException.class)
                .skip(Exception.class)
                .retry(FeignException.class)   // API 일시 장애 시 재시도 (일별 한도 내)
                .retryLimit(3)                 // 3회까지 재시도 후 skip으로 전환
                .listener(animalItemProcessor)  // beforeStep() 호출 보장 (shelterCache/existingAnimalIdMap 초기화)
                .listener(batchSkipListener)   // skip 발생 시 desertionNo/id 로그
                .taskExecutor(batchTaskExecutor) // 멀티스레딩: 청크를 병렬로 처리
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
