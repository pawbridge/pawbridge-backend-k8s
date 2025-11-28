package com.pawbridge.communityservice.service;

/**
 * Outbox 서비스 인터페이스
 * Debezium을 통한 이벤트 발행
 */
public interface OutboxService {

    /**
     * Outbox 이벤트 저장
     * - Debezium이 binlog에서 감지하여 Kafka로 자동 발행
     *
     * @param aggregateType 집합 타입 (예: "Post")
     * @param aggregateId 집합 ID (예: "123")
     * @param eventType 이벤트 타입 (예: "POST_CREATED")
     * @param payload 이벤트 데이터
     * @return 이벤트 ID (UUID)
     */
    String saveEvent(String aggregateType, String aggregateId, String eventType, Object payload);
}
