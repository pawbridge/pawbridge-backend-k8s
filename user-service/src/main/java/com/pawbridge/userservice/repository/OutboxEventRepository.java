package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 오래된 Outbox 이벤트 삭제 (Cleanup Scheduler용)
     * Debezium CDC로 이미 전송된 이벤트를 정리
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.createdAt < :thresholdDate")
    int deleteByCreatedAtBefore(@Param("thresholdDate") LocalDateTime thresholdDate);
}
