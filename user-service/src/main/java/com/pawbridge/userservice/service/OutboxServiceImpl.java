package com.pawbridge.userservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.userservice.entity.OutboxEvent;
import com.pawbridge.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox 이벤트 저장
     * REQUIRES_NEW로 별도 트랜잭션에서 실행 (부모 트랜잭션과 독립)
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveEvent(String aggregateType, String aggregateId, String eventType, String topic, Object payload) {
        try {
            String eventId = UUID.randomUUID().toString();

            // ObjectMapper에 JavaTimeModule 등록 (LocalDateTime 직렬화 지원)
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            // LocalDateTime을 ISO 8601 문자열로 직렬화
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            String payloadJson = mapper.writeValueAsString(payload);

            OutboxEvent outboxEvent = OutboxEvent.of(
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    topic,
                    payloadJson
            );

            outboxEventRepository.save(outboxEvent);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload: {}", e.getMessage());
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        } catch (Exception e) {
            log.error("Failed to save outbox event: {}", e.getMessage());
            throw new RuntimeException("Outbox 이벤트 저장 실패", e);
        }
    }
}
