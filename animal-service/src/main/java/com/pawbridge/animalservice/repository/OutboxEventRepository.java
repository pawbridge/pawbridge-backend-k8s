package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 생성일 기준으로 오래된 이벤트 삭제
     * - Debezium CDC 사용 시 모든 이벤트 정리용
     * - 7일 이상 지난 레코드 삭제
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}
