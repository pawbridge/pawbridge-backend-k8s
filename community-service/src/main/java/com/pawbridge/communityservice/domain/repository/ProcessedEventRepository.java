package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

/**
 * Processed Event Repository
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Idempotency 체크: 이벤트 처리 여부 확인
     * - true: 이미 처리됨 (스킵)
     * - false: 아직 미처리 (처리 진행)
     */
    boolean existsByEventId(String eventId);

    /**
     * Cleanup Scheduler용: 오래된 processed 이벤트 삭제
     * - 30일 지난 이벤트 삭제 (outbox보다 길게 유지)
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoffDate")
    int deleteByProcessedAtBefore(LocalDateTime cutoffDate);
}
