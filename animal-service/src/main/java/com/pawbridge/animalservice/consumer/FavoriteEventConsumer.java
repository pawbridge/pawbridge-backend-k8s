package com.pawbridge.animalservice.consumer;

import com.pawbridge.animalservice.handler.FavoriteEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Favorite 이벤트 Kafka Consumer (Delegator 패턴)
 *
 * 역할:
 * - 단일 토픽(user.favorite.events)에서 이벤트 수신
 * - eventType 필드로 이벤트 구분
 * - 파싱 및 검증만 수행, 비즈니스 로직은 Handler에 위임
 * - 예외 발생 시 throw → DefaultErrorHandler가 재시도 처리
 * - 재시도 실패 시 Recoverer가 보상 트랜잭션 발행
 *
 * 단일 토픽 이유:
 * - favoriteCount는 숫자 필드로 순서가 중요 (DB 조회로 검증 불가)
 * - 동일한 animalId는 동일 파티션으로 전송 → FIFO 보장
 * - 찜 추가(+1) → 찜 취소(-1) 순서 보장 필수
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FavoriteEventConsumer {

    private final FavoriteEventHandler favoriteEventHandler;

    /**
     * Favorite 이벤트 통합 리스너
     *
     * @param payload 이벤트 페이로드 (eventType, eventId, userId, animalId 포함)
     * @param ack     수동 Acknowledgment (처리 성공 시에만 커밋)
     * @throws IllegalArgumentException eventType 또는 eventId 누락/불명 시
     * @throws Exception                비즈니스 로직 실패 시 (DefaultErrorHandler가 재시도)
     */
    @KafkaListener(topics = "user.favorite.events", groupId = "animal-service-favorite-group")
    public void consumeFavoriteEvent(Map<String, Object> payload, Acknowledgment ack) {
        // 1. eventType, eventId 필수 검증
        String eventType = (String) payload.get("eventType");
        String eventId = (String) payload.get("eventId");

        if (eventType == null || eventType.isBlank()) {
            log.error("[CONSUMER] Missing eventType, cannot route event: payload={}", payload);
            throw new IllegalArgumentException("eventType is required");
        }

        if (eventId == null || eventId.isBlank()) {
            log.error("[CONSUMER] Missing eventId, cannot guarantee idempotency: payload={}", payload);
            throw new IllegalArgumentException("eventId is required for idempotency");
        }

        // 2. 필드 추출
        Long animalId = ((Number) payload.get("animalId")).longValue();
        Long userId = ((Number) payload.get("userId")).longValue();

        log.info("[CONSUMER] Received favorite event: eventType={}, eventId={}, animalId={}, userId={}",
                eventType, eventId, animalId, userId);

        // 3. eventType에 따라 Handler 메서드 호출 (Delegator 패턴)
        //    예외 발생 시 throw → DefaultErrorHandler 재시도 → Recoverer 보상 트랜잭션
        switch (eventType) {
            case "FAVORITE_ADDED":
                favoriteEventHandler.addFavorite(eventId, userId, animalId);
                break;

            case "FAVORITE_REMOVED":
                favoriteEventHandler.removeFavorite(eventId, userId, animalId);
                break;

            default:
                log.error("[CONSUMER] Unknown eventType: eventType={}, payload={}", eventType, payload);
                throw new IllegalArgumentException("Unknown eventType: " + eventType);
        }

        // 4. 처리 성공 시 수동 커밋
        //    Handler에서 예외 발생 시 이 라인에 도달하지 않음 → 오프셋 커밋 안 됨 → 재시도
        ack.acknowledge();

        log.info("[CONSUMER] Successfully processed favorite event: eventType={}, eventId={}, animalId={}",
                eventType, eventId, animalId);
    }
}
