package com.pawbridge.animalservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteAddedEvent {

    private String eventId;
    private String eventType;
    private LocalDateTime timestamp;
    private Long userId;
    private Long animalId;
}
