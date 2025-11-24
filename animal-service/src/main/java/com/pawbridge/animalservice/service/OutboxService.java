package com.pawbridge.animalservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.entity.OutboxEvent.OutboxStatus;
import com.pawbridge.animalservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox Pattern 서비스
 * - 트랜잭션과 함께 이벤트를 저장
 * - 비즈니스 로직과 같은 트랜잭션에서 호출되어야 함
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox에 이벤트 저장
     * - 호출하는 메서드의 트랜잭션에 참여
     * - 비즈니스 로직이 롤백되면 이벤트도 롤백됨
     *
     * @param aggregateType Aggregate 타입 (예: "Animal", "Shelter")
     * @param aggregateId Aggregate ID (예: animalId)
     * @param eventType 이벤트 타입 (예: "ANIMAL_CREATED")
     * @param topic Kafka 토픽 이름
     * @param payload 이벤트 객체
     * @return 생성된 이벤트 ID
     */
    @Transactional
    public String saveEvent(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        String eventId = UUID.randomUUID().toString();

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .eventId(eventId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);

            log.debug("[OUTBOX] Event saved: eventId={}, aggregateType={}, aggregateId={}, eventType={}",
                    eventId, aggregateType, aggregateId, eventType);

            return eventId;

        } catch (JsonProcessingException e) {
            log.error("[OUTBOX] Failed to serialize event payload: aggregateType={}, aggregateId={}, error={}",
                    aggregateType, aggregateId, e.getMessage());
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    /**
     * 오래된 발행 완료 이벤트 정리
     * - 7일 이상 지난 PUBLISHED 이벤트 삭제
     */
    @Transactional
    public int cleanupOldEvents(int daysToKeep) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        int deletedCount = outboxEventRepository.deleteByStatusAndPublishedAtBefore(
                OutboxStatus.PUBLISHED, cutoffDate);

        if (deletedCount > 0) {
            log.info("[OUTBOX] Cleaned up {} old published events (older than {} days)",
                    deletedCount, daysToKeep);
        }

        return deletedCount;
    }
}
