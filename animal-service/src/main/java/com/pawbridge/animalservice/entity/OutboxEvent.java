package com.pawbridge.animalservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Outbox Pattern을 위한 이벤트 저장 엔티티
 * - 트랜잭션과 함께 이벤트를 저장하여 원자성 보장
 * - Debezium CDC가 binlog를 읽어 Kafka로 발행
 */
@Entity
@Table(name = "outbox_events",
        indexes = {
                @Index(name = "idx_outbox_created_at", columnList = "createdAt")
        })
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이벤트 고유 ID (멱등성 보장용)
     */
    @Column(nullable = false, unique = true, length = 50)
    private String eventId;

    /**
     * Aggregate 타입 (예: Animal, Shelter)
     */
    @Column(nullable = false, length = 50)
    private String aggregateType;

    /**
     * Aggregate ID (예: animalId, shelterId)
     */
    @Column(nullable = false, length = 50)
    private String aggregateId;

    /**
     * 이벤트 타입 (예: ANIMAL_CREATED, ANIMAL_STATUS_CHANGED)
     */
    @Column(nullable = false, length = 50)
    private String eventType;

    /**
     * Kafka 토픽 이름
     */
    @Column(nullable = false, length = 100)
    private String topic;

    /**
     * 이벤트 페이로드 (JSON)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * 생성 시각
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
