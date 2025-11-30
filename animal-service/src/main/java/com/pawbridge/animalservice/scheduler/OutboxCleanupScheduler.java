package com.pawbridge.animalservice.scheduler;

import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 정리 스케줄러
 * - Debezium CDC 사용 시 OutboxEvent 레코드는 자동 삭제되지 않음
 * - 주기적으로 오래된 레코드를 정리하여 DB 용량 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;

    private static final int DAYS_TO_KEEP = 7;  // 7일 보관

    /**
     * 매일 새벽 3시에 오래된 이벤트 정리
     * - 7일 이상 지난 레코드 삭제
     * - Debezium은 binlog를 읽으므로 레코드 삭제해도 문제없음
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupOldEvents() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(DAYS_TO_KEEP);

        // 7일 이상 지난 모든 이벤트 삭제 (status 무관)
        int deletedCount = outboxEventRepository.deleteByCreatedAtBefore(cutoffDate);

        if (deletedCount > 0) {
            log.info("[OUTBOX-CLEANUP] Deleted {} old events (older than {} days)",
                    deletedCount, DAYS_TO_KEEP);
        }
    }
}
