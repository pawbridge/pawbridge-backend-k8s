package com.pawbridge.userservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "processed_events",
        indexes = {
                @Index(name = "idx_processed_at", columnList = "processed_at")
        }
)
@EntityListeners(AuditingEntityListener.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @CreatedDate
    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;

    /**
     * ProcessedEvent 생성 (정적 팩토리 메서드)
     */
    public static ProcessedEvent of(String eventId, String eventType) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build();
    }
}
