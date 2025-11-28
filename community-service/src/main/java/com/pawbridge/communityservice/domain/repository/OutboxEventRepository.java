package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

/**
 * Outbox Event Repository
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Cleanup Scheduler용: 오래된 outbox 이벤트 삭제
     * - 7일 지난 이벤트 삭제
     * - Debezium은 INSERT만 처리하므로 DELETE 이벤트는 무시됨
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.createdAt < :cutoffDate")
    int deleteByCreatedAtBefore(LocalDateTime cutoffDate);
}
