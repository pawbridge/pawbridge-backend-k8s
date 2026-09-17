package com.pawbridge.animalservice.batch.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 배치 공용 ThreadPoolTaskExecutor 설정
 * - APMS 저장 Step의 병렬 chunk 처리에 사용
 */
@Configuration
public class BatchExecutorConfig {

    private static final int THREAD_POOL_SIZE = 4;

    /**
     * Step 1 ThreadPoolTaskExecutor
     * - 청크 병렬 처리 (4 스레드 × ~1 API RPS = 4 RPS, APMS 30 TPS 한도 내)
     */
    @Bean(name = "batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREAD_POOL_SIZE);
        executor.setMaxPoolSize(THREAD_POOL_SIZE);
        // 기존 chunk 제출 동작을 유지한다. ES 인덱싱은 제한 시간이 있는 전용 executor를 사용한다.
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("batch-");
        executor.initialize();
        return executor;
    }
}
