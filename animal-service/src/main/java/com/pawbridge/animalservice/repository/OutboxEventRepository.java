package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.entity.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 발행 대기 중인 이벤트 조회 (PENDING 상태, 생성 시간 순)
     * - 스케줄러가 폴링할 때 사용
     * - 한 번에 최대 100개씩 처리
     */
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /**
     * 오래된 발행 완료 이벤트 삭제
     * - 보관 기간이 지난 이벤트 정리
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.status = :status AND o.publishedAt < :before")
    int deleteByStatusAndPublishedAtBefore(
            @Param("status") OutboxStatus status,
            @Param("before") LocalDateTime before
    );

    /**
     * 상태별 이벤트 수 조회 (모니터링용)
     */
    long countByStatus(OutboxStatus status);
}
