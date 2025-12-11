package com.pawbridge.userservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FavoriteCompensationEvent {

    private String eventId;
    private String originalEventId;
    private String compensationType;  // "ROLLBACK_FAVORITE_ADDED" or "ROLLBACK_FAVORITE_REMOVED"
    private Long userId;
    private Long animalId;
    private String reason;
    private LocalDateTime timestamp;

    /**
     * FavoriteCompensationEvent 생성 (정적 팩토리 메서드)
     */
    public static FavoriteCompensationEvent of(String eventId, String originalEventId,
                                               String compensationType, Long userId,
                                               Long animalId, String reason) {
        return FavoriteCompensationEvent.builder()
                .eventId(eventId)
                .originalEventId(originalEventId)
                .compensationType(compensationType)
                .userId(userId)
                .animalId(animalId)
                .reason(reason)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
