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
public class FavoriteRemovedEvent {

    private String eventType;
    private String eventId;
    private Long userId;
    private Long animalId;
    private LocalDateTime timestamp;

    /**
     * FavoriteRemovedEvent 생성 (정적 팩토리 메서드)
     */
    public static FavoriteRemovedEvent of(String eventId, Long userId, Long animalId) {
        return FavoriteRemovedEvent.builder()
                .eventType("FAVORITE_REMOVED")
                .eventId(eventId)
                .userId(userId)
                .animalId(animalId)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
