package com.pawbridge.communityservice.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Processed Event 엔티티: 이벤트 중복 처리 방지용 테이블
 *
 * Idempotency 보장:
 * - event_id를 PK로 사용하여 중복 INSERT 방지
 * - Kafka 메시지 재처리 시 이미 처리된 이벤트는 스킵
 *
 * Race Condition 방지:
 * - Handler에서 Elasticsearch 저장 전에 먼저 INSERT
 * - 실패 시 재시도 가능하도록 순서 보장
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public static ProcessedEvent of(String eventId, String eventType) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
