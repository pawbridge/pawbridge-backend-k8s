package com.pawbridge.userservice.service;

public interface OutboxService {

    /**
     * Outbox 이벤트 저장
     * @param aggregateType 집합체 타입 (예: "Favorite")
     * @param aggregateId 집합체 ID (예: userId)
     * @param eventType 이벤트 타입 (예: "FAVORITE_ADDED")
     * @param topic Kafka 토픽 (예: "user.favorite.events")
     * @param payload JSON 형식의 이벤트 페이로드
     */
    void saveEvent(String aggregateType, String aggregateId, String eventType, String topic, Object payload);
}
