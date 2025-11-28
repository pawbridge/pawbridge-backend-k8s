package com.pawbridge.communityservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import com.pawbridge.communityservice.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox 서비스 구현체
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox 이벤트 저장
     *
     * 동작 흐름:
     * 1. payload를 JSON 문자열로 변환
     * 2. UUID 생성 (Idempotency 체크용)
     * 3. outbox_events 테이블에 INSERT
     * 4. Debezium이 binlog에서 감지
     * 5. Kafka로 자동 발행
     */
    @Override
    @Transactional
    public String saveEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        String eventId = UUID.randomUUID().toString();

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .eventId(eventId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .type(eventType)
                    .payload(payloadJson)
                    .createdAt(LocalDateTime.now())
                    .build();

            outboxEventRepository.save(event);
            log.info("📤 Outbox event saved: eventId={}, type={}", eventId, eventType);

            return eventId;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload", e);
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }
}
