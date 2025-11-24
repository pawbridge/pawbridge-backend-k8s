package com.pawbridge.animalservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Consumer 멱등성을 위한 처리된 이벤트 기록 엔티티
 * - eventId로 중복 처리 방지
 * - 같은 이벤트가 여러 번 전달되어도 한 번만 처리
 */
@Entity
@Table(name = "processed_events",
        indexes = {
                @Index(name = "idx_processed_at", columnList = "processedAt")
        })
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    /**
     * 이벤트 고유 ID (Producer가 생성한 UUID)
     */
    @Id
    @Column(length = 50)
    private String eventId;

    /**
     * 이벤트 타입 (디버깅/모니터링용)
     */
    @Column(nullable = false, length = 50)
    private String eventType;

    /**
     * 처리 시각
     */
    @Column(nullable = false)
    private LocalDateTime processedAt;

    /**
     * 정적 팩토리 메서드
     */
    public static ProcessedEvent of(String eventId, String eventType) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
