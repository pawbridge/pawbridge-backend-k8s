package com.pawbridge.animalservice.batch.job;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.batch.ApmsQueryProgress;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.reader.ApmsItemReader;
import com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.FlowJobBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * APMS API 동기화 Batch Job 설정
 * - APMS API로부터 유기동물 데이터를 조회하여 DB에 저장
 * - ES 모드는 저장 후 인덱싱, PostgreSQL 모드는 청크 트랜잭션에서 검색 문서 반영
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
    private final ShelterPrepTasklet shelterPrepTasklet;

    // BatchExecutorConfig에서 정의 — 순환 참조 방지를 위해 분리
    @Autowired
    @Qualifier("batchTaskExecutor")
    private TaskExecutor batchTaskExecutor;

    private static final int CHUNK_SIZE = 1000; // 수집 완료된 snapshot을 청크 단위로 저장

    /**
     * APMS 동물 동기화 Job
     * - Step 0: 보호소 사전 저장 (ShelterPrepTasklet)
     * - Step 1: APMS API → 선택한 관계형 DB 저장 (Step 0 성공 시에만 실행)
     * - Step 2: ES 모드에서만 Elasticsearch 인덱싱
     *
     * Step Flow: Step 0 FAILED → Job 즉시 종료 (Step 1, 2 실행 안 함)
     */
    @Bean
    public Job apmsAnimalSyncJob(
            @Value("${pawbridge.animal-query.backend:elasticsearch}") String backend,
            @Qualifier("elasticsearchIndexStep") ObjectProvider<Step> elasticsearchStep) {
        FlowBuilder<FlowJobBuilder> flow = new JobBuilder("apmsAnimalSyncJob", jobRepository)
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution execution) {
                        execution.getExecutionContext().putString(ApmsQueryProgress.BACKEND_KEY, backend);
                    }
                })
                .start(shelterPrepStep()).on("FAILED").fail()
                .from(shelterPrepStep()).on("*").to(apmsAnimalSyncStep())
                .from(apmsAnimalSyncStep());
        if ("elasticsearch".equals(backend)) {
            flow.next(elasticsearchStep.getObject());
        } else if (!"postgresql".equals(backend)) {
            throw new IllegalArgumentException("Unknown animal query backend: " + backend);
        }
        // PostgreSQL search documents already commit with each shelter/animal write.
        // A separate ES indexing step must not run after the PostgreSQL cutover.
        return flow.next(apmsCollectionVerificationStep()).end().build();
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
     * - Reader: Step 0에서 수집한 snapshot 순회
     * - Processor: DTO → Entity 변환 (shelterCache/existingAnimalIdMap 캐시 조회)
     * - Writer: 신규 saveAll(), 기존 @Modifying UPDATE
     * - 읽기/변환/저장 실패 시 Step 실패: 누락을 정상 완료로 처리하지 않음
     */
    @Bean
    public Step apmsAnimalSyncStep() {
        return new StepBuilder("apmsAnimalSyncStep", jobRepository)
                .<ApmsAnimal, Animal>chunk(CHUNK_SIZE, transactionManager)
                .reader(apmsItemReader)
                .processor(animalItemProcessor)
                .writer(animalItemWriter)
                .listener(animalItemProcessor)  // beforeStep() 호출 보장 (shelterCache/existingAnimalIdMap 초기화)
                .faultTolerant()
                .retry(CannotAcquireLockException.class)
                .retryLimit(3)
                .taskExecutor(batchTaskExecutor) // 멀티스레딩: 청크를 병렬로 처리
                .build();
    }

    /**
     * Elasticsearch 인덱싱 Step
     * - MySQL → ES upsert (기존 인덱스 유지, image_vector 보존)
     * - doc_as_upsert: true → 신규 문서는 insert, 기존 문서는 partial update
     */
    @Bean
    @ConditionalOnProperty(prefix="pawbridge.animal-query", name="backend", havingValue="elasticsearch", matchIfMissing=true)
    public Step elasticsearchIndexStep(ElasticsearchIndexService elasticsearchIndexService) {
        return new StepBuilder("elasticsearchIndexStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    log.info("[BATCH] Elasticsearch 인덱싱 Step 시작");
                    long indexedCount = elasticsearchIndexService.indexAllAnimals();
                    log.info("[BATCH] Elasticsearch 인덱싱 Step 완료: {} 건", indexedCount);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
    /** Persist healthy records and search updates before reporting any unresolved collection gap. */
    @Bean
    public Step apmsCollectionVerificationStep() {
        return new StepBuilder("apmsCollectionVerificationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    var execution = contribution.getStepExecution().getJobExecution();
                    int incomplete = execution.getExecutionContext()
                            .getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0);
                    int countMismatches = execution.getExecutionContext()
                            .getInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, 0);
                    if (incomplete > 0 && incomplete == countMismatches) {
                        log.warn("[BATCH] APMS count mismatch remains retryable: queries={}", incomplete);
                        return RepeatStatus.FINISHED;
                    }
                    if (incomplete > 0) {
                        throw new IllegalStateException("APMS collection incomplete; inspect job execution context");
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

}
