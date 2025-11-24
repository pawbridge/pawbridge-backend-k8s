package com.pawbridge.animalservice.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.entity.OutboxEvent.OutboxStatus;
import com.pawbridge.animalservice.repository.OutboxEventRepository;
import com.pawbridge.animalservice.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 이벤트 발행 스케줄러
 * - 주기적으로 PENDING 상태의 이벤트를 폴링하여 Kafka로 발행
 * - 발행 성공 시 PUBLISHED 상태로 변경
 * - 발행 실패 시 재시도 (최대 3회)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxService outboxService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final int KAFKA_SEND_TIMEOUT_SECONDS = 10;
    private static final int DAYS_TO_KEEP_PUBLISHED_EVENTS = 7;

    /**
     * 1초마다 Outbox 테이블을 폴링하여 이벤트 발행
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
                .findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("[OUTBOX-PUBLISHER] Found {} pending events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            publishEvent(event);
        }
    }

    /**
     * 단일 이벤트 발행
     */
    private void publishEvent(OutboxEvent event) {
        try {
            // payload는 이미 JSON 문자열이므로 Object로 역직렬화
            // JsonSerializer가 다시 직렬화하여 올바른 JSON으로 전송
            Object eventPayload = objectMapper.readValue(event.getPayload(), Object.class);

            // 동기식 발행 (결과 확인)
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), eventPayload)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 발행 성공
            event.markAsPublished();
            outboxEventRepository.save(event);

            log.info("[OUTBOX-PUBLISHER] Published event: eventId={}, topic={}, aggregateId={}",
                    event.getEventId(), event.getTopic(), event.getAggregateId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlePublishFailure(event, e);
        } catch (ExecutionException | TimeoutException e) {
            handlePublishFailure(event, e);
        } catch (Exception e) {
            // JSON 파싱 오류 등
            handlePublishFailure(event, e);
        }
    }

    /**
     * 발행 실패 처리
     */
    private void handlePublishFailure(OutboxEvent event, Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }

        event.markAsFailed(errorMessage);
        outboxEventRepository.save(event);

        if (event.getStatus() == OutboxStatus.FAILED) {
            log.error("[OUTBOX-PUBLISHER] Event failed permanently after 3 retries: eventId={}, topic={}, error={}",
                    event.getEventId(), event.getTopic(), errorMessage);
        } else {
            log.warn("[OUTBOX-PUBLISHER] Event publish failed, will retry: eventId={}, retryCount={}, error={}",
                    event.getEventId(), event.getRetryCount(), errorMessage);
        }
    }

    /**
     * 매일 새벽 3시에 오래된 발행 완료 이벤트 정리
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldPublishedEvents() {
        int deletedCount = outboxService.cleanupOldEvents(DAYS_TO_KEEP_PUBLISHED_EVENTS);
        log.info("[OUTBOX-PUBLISHER] Cleanup completed: {} events deleted", deletedCount);
    }
}
