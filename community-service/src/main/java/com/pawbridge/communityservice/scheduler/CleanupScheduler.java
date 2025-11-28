package com.pawbridge.communityservice.scheduler;

import com.pawbridge.communityservice.domain.repository.OutboxEventRepository;
import com.pawbridge.communityservice.domain.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Cleanup Scheduler: 오래된 이벤트 데이터 삭제
 *
 * 목적: 테이블 무한 증가 방지
 * - outbox_events: 7일 지난 데이터 삭제
 * - processed_events: 30일 지난 데이터 삭제
 *
 * 참고: Debezium은 INSERT만 처리하므로 DELETE 이벤트는 무시됨
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * 매일 새벽 3시: outbox_events 정리
     * Retention: 7일
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupOldOutboxEvents() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(7);
        int deleted = outboxEventRepository.deleteByCreatedAtBefore(cutoffDate);

        log.info("🧹 Cleaned up {} old outbox events (older than {})", deleted, cutoffDate);
    }

    /**
     * 매일 새벽 4시: processed_events 정리
     * Retention: 30일 (outbox보다 길게 유지)
     */
    @Scheduled(cron = "0 0 4 * * ?")
    @Transactional
    public void cleanupOldProcessedEvents() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
        int deleted = processedEventRepository.deleteByProcessedAtBefore(cutoffDate);

        log.info("🧹 Cleaned up {} old processed events (older than {})", deleted, cutoffDate);
    }
}
