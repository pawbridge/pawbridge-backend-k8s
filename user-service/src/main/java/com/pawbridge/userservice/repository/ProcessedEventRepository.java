package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * 이벤트 처리 여부 확인 (Idempotency)
     */
    boolean existsByEventId(String eventId);

    /**
     * 오래된 ProcessedEvent 삭제 (Cleanup Scheduler용)
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :thresholdDate")
    int deleteByProcessedAtBefore(@Param("thresholdDate") LocalDateTime thresholdDate);
}
