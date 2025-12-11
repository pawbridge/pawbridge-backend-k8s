package com.pawbridge.userservice.scheduler;

import com.pawbridge.userservice.repository.OutboxEventRepository;
import com.pawbridge.userservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * Outbox 이벤트 정리 (매일 새벽 3시)
     * Debezium CDC로 전송된 이벤트는 7일 후 삭제
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOutboxEvents() {
        LocalDateTime thresholdDate = LocalDateTime.now().minusDays(7);

        try {
            int deletedCount = outboxEventRepository.deleteByCreatedAtBefore(thresholdDate);
            if (deletedCount > 0) {
                log.info("[CLEANUP] Deleted {} outbox events older than {}", deletedCount, thresholdDate);
            }
        } catch (Exception e) {
            log.error("[CLEANUP] Failed to cleanup outbox events: {}", e.getMessage());
        }
    }

    /**
     * ProcessedEvent 정리 (매일 새벽 3시 30분)
     * 처리된 보상 이벤트는 30일 후 삭제
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanupProcessedEvents() {
        LocalDateTime thresholdDate = LocalDateTime.now().minusDays(30);

        try {
            int deletedCount = processedEventRepository.deleteByProcessedAtBefore(thresholdDate);
            if (deletedCount > 0) {
                log.info("[CLEANUP] Deleted {} processed events older than {}", deletedCount, thresholdDate);
            }
        } catch (Exception e) {
            log.error("[CLEANUP] Failed to cleanup processed events: {}", e.getMessage());
        }
    }
}
