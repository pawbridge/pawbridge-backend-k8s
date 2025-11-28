package com.pawbridge.communityservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka Consumer 설정
 *
 * 에러 핸들링:
 * - 3회 재시도 (1초 간격)
 * - 실패 시 로그만 남김 (보상 트랜잭션 없음)
 * - SyncScheduler가 주기적으로 누락 복구
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 에러 핸들러: 3회 재시도, 1초 간격
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("❌ Kafka message processing failed after retries: {}", record, exception);
                },
                new FixedBackOff(1000L, 3L)  // 1초 간격, 3회 재시도
        );

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
