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

    /**
     * 에러 핸들러 설정 (재시도 + 보상 트랜잭션)
     *
     * 동작:
     * 1. 재시도: 1초 간격, 최대 3회
     * 2. 재시도 실패 시 Recoverer 호출 → 보상 트랜잭션 발행
     *
     * Recoverer (Saga 패턴 보상 트랜잭션):
     * - FAVORITE_ADDED 실패 → user-service에 ROLLBACK_FAVORITE_ADDED 이벤트 발행
     *   (user-service가 삭제한 favorite 레코드를 다시 삭제)
     * - FAVORITE_REMOVED 실패 → 보상 불필요 (로그만 남김, Eventually Consistent)
     *
     * FATAL-ERROR 처리:
     * - 보상 이벤트 저장 실패 시 try-catch로 잡아서 FATAL-ERROR 로그 남김
     * - Consumer 블로킹 방지 (무한 루프 방지)
     * - 운영팀 수동 개입 필요 (Slack/PagerDuty 연동 권장)
     */
    @Bean
    public CommonErrorHandler errorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> {
                    // ===== Recoverer: 모든 재시도 실패 시 호출 =====
                    try {
                        // 1. 페이로드 파싱
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = (Map<String, Object>) record.value();
                        String eventType = (String) payload.get("eventType");
                        String eventId = (String) payload.get("eventId");
                        Long userId = ((Number) payload.get("userId")).longValue();
                        Long animalId = ((Number) payload.get("animalId")).longValue();

                        log.error("[RECOVERER] All retries exhausted for topic={}, partition={}, offset={}, eventType={}, eventId={}",
                                record.topic(), record.partition(), record.offset(), eventType, eventId);

                        // 2. eventType에 따라 보상 전략 결정
                        switch (eventType) {
                            case "FAVORITE_ADDED":
                                // FAVORITE_ADDED 실패 → user-service에 보상 이벤트 발행
                                // user-service는 favorite 레코드 삭제 (롤백)
                                FavoriteCompensationEvent compensationEvent =
                                        FavoriteCompensationEvent.forAddedFailure(
                                                eventId,
                                                userId,
                                                animalId,
                                                "animal-service failed to increment favoriteCount after " +
                                                        MAX_RETRY_ATTEMPTS + " retries. Error: " + ex.getMessage()
                                        );

                                // Outbox 패턴으로 보상 이벤트 발행
                                outboxService.saveEvent(
                                        "user.compensation.events",
                                        userId.toString(),
                                        compensationEvent
                                );

                                log.warn("[RECOVERER] Compensation event published for FAVORITE_ADDED: " +
                                                "eventId={}, userId={}, animalId={}, compensationEventId={}",
                                        eventId, userId, animalId, compensationEvent.getEventId());
                                break;

                            case "FAVORITE_REMOVED":
                                // FAVORITE_REMOVED 실패 → 보상 불필요
                                // 이유: user-service는 이미 favorite 삭제 완료 (사용자 의도 달성)
                                //       animal-service의 favoriteCount 불일치는 Eventually Consistent로 처리
                                //       (정기 배치 또는 다음 찜 추가 시 자연스럽게 맞춰짐)
                                log.warn("[RECOVERER] FAVORITE_REMOVED failed, but compensation NOT needed. " +
                                                "favoriteCount will be eventually consistent. " +
                                                "eventId={}, userId={}, animalId={}",
                                        eventId, userId, animalId);
                                break;

                            default:
                                log.error("[RECOVERER] Unknown eventType, cannot determine compensation strategy: " +
                                                "eventType={}, eventId={}, payload={}",
                                        eventType, eventId, payload);
                        }

                    } catch (Exception compensationEx) {
                        // ===== FATAL-ERROR: 보상 트랜잭션 자체가 실패 =====
                        // try-catch로 감싸서 Consumer 블로킹 방지
                        // 운영팀 수동 개입 필요 (Slack/PagerDuty 알림 권장)
                        log.error("""
                                        ╔══════════════════════════════════════════════════════════════════╗
                                        ║                         🚨 FATAL-ERROR 🚨                        ║
                                        ║          Compensation transaction failed to save!                ║
                                        ║              Manual intervention required!                       ║
                                        ╚══════════════════════════════════════════════════════════════════╝

                                        Topic: {}
                                        Partition: {}
                                        Offset: {}

                                        Original Payload:
                                        {}

                                        Original Error:
                                        {}

                                        Compensation Error:
                                        {}

                                        Action Required:
                                        1. Check user-service database for inconsistent favorite records
                                        2. Manually publish compensation event or fix data
                                        3. Investigate why OutboxService.saveEvent() failed
                                        4. Monitor for cascading failures
                                        """,
                                record.topic(),
                                record.partition(),
                                record.offset(),
                                record.value(),
                                ex.getMessage(),
                                compensationEx.getMessage(),
                                compensationEx
                        );

                        // Consumer가 멈추지 않도록 예외를 삼킴 (swallow)
                        // DLT로 전송하지 않음 (보상 실패는 수동 처리 필요)
                    }
                },
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS)
        );

        // 재시도 로그
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("[KAFKA-RETRY] Retry attempt {}/{} for topic={}, partition={}, offset={}",
                        deliveryAttempt, MAX_RETRY_ATTEMPTS, record.topic(), record.partition(), record.offset())
        );

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // 수동 커밋 모드 설정
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // 에러 핸들러 설정 (재시도 + DLQ)
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
