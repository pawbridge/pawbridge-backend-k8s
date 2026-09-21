package com.pawbridge.animalservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.event.FavoriteCompensationEvent;
import com.pawbridge.animalservice.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // 재시도 설정
    private static final long RETRY_INTERVAL_MS = 1000L; // 1초 간격
    private static final long MAX_RETRY_ATTEMPTS = 3L;   // 최대 3회 재시도

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // JSON 역직렬화 설정
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.pawbridge.*");
        configProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        configProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");

        // Consumer 설정
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /** A failed record advances only after its compensation is durably stored. */
    @Bean
    public CommonErrorHandler errorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, failure) -> {
            if (!(record.value() instanceof Map<?, ?> payload)
                    || !(payload.get("eventId") instanceof String eventId) || eventId.isBlank()
                    || !(payload.get("eventType") instanceof String eventType)
                    || !(payload.get("userId") instanceof Number userId)
                    || !(payload.get("animalId") instanceof Number animalId)) {
                throw new IllegalStateException("Favorite recovery requires traceable event fields", failure);
            }
            if (!"FAVORITE_ADDED".equals(eventType)) {
                // No durable recovery exists for a failed decrement or an unknown event.
                // Retain the offset instead of assuming a future write repairs the count.
                throw new IllegalStateException("Favorite event recovery is unresolved: " + eventType, failure);
            }
            FavoriteCompensationEvent compensation = FavoriteCompensationEvent.forAddedFailure(
                    eventId, userId.longValue(), animalId.longValue(),
                    "animal-service failed to increment favoriteCount after " + MAX_RETRY_ATTEMPTS + " retries");
            // The proxied service commits its Outbox transaction before this call returns.
            // A storage failure must propagate so Spring Kafka retains/retries this record.
            outboxService.saveEvent("FavoriteCompensation", userId.toString(),
                    "FAVORITE_COMPENSATION_REQUIRED", "user.compensation.events", compensation);
            log.warn("[RECOVERER] Compensation stored: topic={}, partition={}, offset={}, originalEventId={}",
                    record.topic(), record.partition(), record.offset(), eventId);
        }, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS));
        errorHandler.setCommitRecovered(true);
        errorHandler.setRetryListeners((record, failure, attempt) ->
                log.warn("[KAFKA-RETRY] attempt={}, topic={}, partition={}, offset={}",
                        attempt, record.topic(), record.partition(), record.offset()));
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // 수동 커밋 모드 설정
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 에러 핸들러 설정 (재시도 + DLQ)
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
