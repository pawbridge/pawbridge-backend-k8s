package com.pawbridge.communityservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.communityservice.search.PostgresqlSearchDocuments;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Optional;
import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import com.pawbridge.communityservice.domain.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox 서비스 구현체
 */
@Service
@Slf4j
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Optional<PostgresqlSearchDocuments> searchDocuments;

    @Autowired
    public OutboxServiceImpl(OutboxEventRepository repository, ObjectMapper mapper,
            Optional<PostgresqlSearchDocuments> documents) {
        this.outboxEventRepository = repository;
        this.objectMapper = mapper;
        this.searchDocuments = documents;
    }

    public OutboxServiceImpl(OutboxEventRepository repository, ObjectMapper mapper) {
        this(repository, mapper, Optional.empty());
    }

    /**
     * Outbox 이벤트 저장
     *
     * 동작 흐름:
     * 1. payload를 JSON 문자열로 변환
     * 2. UUID 생성 (Idempotency 체크용)
     * 3. outbox_events 테이블에 INSERT
     * 4. PostgreSQL 선택 시 검색 문서도 같은 트랜잭션에서 저장
     * 5. Debezium이 커밋된 변경을 감지
     * 6. Kafka로 자동 발행
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
            if (searchDocuments.isPresent() && "Post".equals(aggregateType)) {
                long postId = Long.parseLong(aggregateId);
                if ("POST_DELETED".equals(eventType)) {
                    searchDocuments.get().delete(postId);
                } else if ("POST_CREATED".equals(eventType) || "POST_UPDATED".equals(eventType)) {
                    JsonNode document = objectMapper.readTree(payloadJson);
                    if (!document.path("title").isTextual() || !document.path("content").isTextual()) {
                        throw new IllegalArgumentException("Post search source is missing");
                    }
                    searchDocuments.get().write(postId, document.get("title").asText(), document.get("content").asText());
                }
            }
            log.info("📤 Outbox event saved: eventId={}, type={}", eventId, eventType);

            return eventId;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload", e);
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }
}
