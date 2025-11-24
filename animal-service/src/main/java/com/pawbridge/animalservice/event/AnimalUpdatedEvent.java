package com.pawbridge.animalservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalUpdatedEvent {

    private String eventId;
    private String eventType;
    private LocalDateTime timestamp;
    private Long animalId;
    private Map<String, Object> updatedFields;
}
