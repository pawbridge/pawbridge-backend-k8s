package com.pawbridge.animalservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 찜 이벤트 처리 실패 시 보상을 위한 이벤트
 * - user-service가 구독하여 원래 작업을 롤백
 * - Saga 패턴의 보상 트랜잭션 구현
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteCompensationEvent {

    /**
     * 보상 이벤트 고유 ID (멱등성)
     */
    private String eventId;

    /**
     * 이벤트 타입 고정값
     */
    private String eventType;

    /**
     * 보상 이벤트 발생 시각
     */
    private LocalDateTime timestamp;

    /**
     * 실패한 원본 이벤트 ID
     */
    private String originalEventId;

    /**
     * 사용자 ID
     */
    private Long userId;

    /**
     * 동물 ID
     */
    private Long animalId;

    /**
     * 보상 유형
     * - ROLLBACK_FAVORITE_ADDED: 찜 추가 롤백 (favorite 삭제)
     * - ROLLBACK_FAVORITE_REMOVED: 찜 취소 롤백 (favorite 복구)
     */
    private String compensationType;

    /**
     * 실패 원인 (최대 500자)
     */
    private String failureReason;

    /**
     * Factory 메서드: 찜 추가 실패 시 보상 이벤트 생성
     */
    public static FavoriteCompensationEvent forAddedFailure(
            String originalEventId,
            Long userId,
            Long animalId,
            String failureReason
    ) {
        return FavoriteCompensationEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .eventType("FAVORITE_COMPENSATION_REQUIRED")
                .timestamp(LocalDateTime.now())
                .originalEventId(originalEventId)
                .userId(userId)
                .animalId(animalId)
                .compensationType("ROLLBACK_FAVORITE_ADDED")
                .failureReason(truncate(failureReason, 500))
                .build();
    }

    /**
     * Factory 메서드: 찜 취소 실패 시 보상 이벤트 생성
     *
     * ⚠️ 주의: ROLLBACK_FAVORITE_REMOVED는 실제로 필요 없음!
     *
     * 이유:
     * - user-service는 이미 favorite 삭제 완료 (사용자 의도 달성)
     * - animal-service는 favoriteCount 감소 실패
     * - 보상으로 favorite 복구? → 사용자 의도와 반대!
     * - 정합성은 정기 배치 또는 다음 찜 추가 시 자연스럽게 맞춰짐
     *
     * 따라서 이 메서드는 사용하지 않음 (로그만 남김)
     *
     * @deprecated 보상 불필요, 사용 금지
     */
    @Deprecated
    public static FavoriteCompensationEvent forRemovedFailure(
            String originalEventId,
            Long userId,
            Long animalId,
            String failureReason
    ) {
        throw new UnsupportedOperationException(
            "ROLLBACK_FAVORITE_REMOVED is not supported. " +
            "Compensation for FavoriteRemovedEvent is not needed. " +
            "favoriteCount will be eventually consistent."
        );
    }

    /**
     * 오류 메시지 길이 제한
     */
    private static String truncate(String message, int maxLength) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > maxLength
                ? message.substring(0, maxLength)
                : message;
    }
}
