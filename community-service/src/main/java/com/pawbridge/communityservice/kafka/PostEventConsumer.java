package com.pawbridge.communityservice.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka Consumer: community.post.events 토픽 구독
 *
 * Debezium Outbox Router 메시지 구조:
 * - Headers: id (event_id), eventType (type)
 * - Value: payload JSON (Elasticsearch 저장용 데이터)
 *
 * Delegator 패턴:
 * - Consumer는 메시지 수신 및 라우팅만
 * - Handler가 실제 비즈니스 로직 처리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventConsumer {

    private final PostEventHandler postEventHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "community.post.events", groupId = "${spring.kafka.consumer.group-id}")
    public void consumePostEvent(ConsumerRecord<String, String> record) {
        try {
            // 1. Headers에서 메타데이터 추출
            String eventId = extractHeader(record, "id");
            String eventType = extractHeader(record, "eventType");

            log.info("📥 Received event: eventId={}, eventType={}", eventId, eventType);

            // 2. EventRouter가 확장한 JSON 본문 자체가 게시글 payload다.
            Map<String, Object> payload = objectMapper.readValue(record.value(), new TypeReference<>() {});
            if (payload == null || payload.isEmpty()) {
                throw new IllegalArgumentException("Post event payload must be a non-empty object");
            }

            // 3. eventType에 따라 Handler로 라우팅
            switch (eventType) {
                case "POST_CREATED" -> postEventHandler.indexPost(eventId, payload);
                case "POST_UPDATED" -> postEventHandler.updatePost(eventId, payload);
                case "POST_DELETED" -> postEventHandler.deletePost(eventId, payload);
                default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
            }

        } catch (Exception e) {
            log.error("❌ Failed to consume post event", e);
            throw new RuntimeException(e);  // Kafka retry
        }
    }

    /**
     * Kafka Header 추출 헬퍼 메서드
     */
    private String extractHeader(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null || header.value() == null) {
            throw new IllegalArgumentException("Header not found: " + key);
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Header is blank: " + key);
        }
        return value;
    }
}
