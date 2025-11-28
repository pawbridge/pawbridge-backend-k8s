package com.pawbridge.communityservice.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox Event 엔티티: Debezium CDC용 이벤트 테이블
 *
 * Debezium 방식 특징:
 * - status, publishedAt, retryCount 필드 없음 (Polling 방식과 차이)
 * - Debezium이 binlog에서 INSERT를 감지하여 자동으로 Kafka 발행
 *
 * 컬럼 설명:
 * - outbox_id: AUTO_INCREMENT PK (Debezium 내부 사용)
 * - event_id: UUID (Idempotency 체크용)
 * - aggregate_type: 집합 타입 (예: "Post")
 * - aggregate_id: 집합 ID (예: "123")
 * - type: 이벤트 타입 (예: "POST_CREATED")
 * - payload: 이벤트 데이터 (JSON)
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "event_id", unique = true, nullable = false)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
