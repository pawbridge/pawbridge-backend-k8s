package com.pawbridge.animalservice.batch.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 배치 공용 ThreadPoolTaskExecutor 설정
 * - ApmsAnimalBatchJob에서 분리하여 순환 참조 방지
 *   (ElasticsearchIndexService가 batchTaskExecutor를 주입받으므로
 *    ApmsAnimalBatchJob에 두면 순환 의존성 발생)
 */
@Configuration
public class BatchExecutorConfig {

    private static final int THREAD_POOL_SIZE = 4;

    /**
     * Step 1, 2 공용 ThreadPoolTaskExecutor
     * - Step 1: 청크 병렬 처리 (4 스레드 × ~1 API RPS = 4 RPS, APMS 30 TPS 한도 내)
     * - Step 2: ES 페이지 병렬 인덱싱 (ElasticsearchIndexService에서 주입받아 사용)
     */
    @Bean(name = "batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREAD_POOL_SIZE);
        executor.setMaxPoolSize(THREAD_POOL_SIZE);
        // Integer.MAX_VALUE: ES 병렬 인덱싱 시 전체 페이지(~16개)를 한 번에 제출
        // queueCapacity < totalPages - corePoolSize 이면 RejectedExecutionException 발생
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("batch-");
        executor.initialize();
        return executor;
    }
}
