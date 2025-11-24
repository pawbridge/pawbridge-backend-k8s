package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * 이벤트 ID로 처리 여부 확인 (멱등성 체크)
     */
    boolean existsByEventId(String eventId);

    /**
     * 오래된 처리 기록 삭제
     * - 보관 기간이 지난 기록 정리
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
    int deleteByProcessedAtBefore(@Param("before") LocalDateTime before);
}
