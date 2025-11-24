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
public class AnimalStatusChangedEvent {

    private String eventId;
    private String eventType;
    private LocalDateTime timestamp;
    private Long animalId;
    private String oldStatus;
    private String newStatus;
    private String animalName;
    private String species;
}
