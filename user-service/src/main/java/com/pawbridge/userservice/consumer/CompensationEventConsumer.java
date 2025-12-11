package com.pawbridge.userservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.userservice.event.FavoriteCompensationEvent;
import com.pawbridge.userservice.handler.CompensationEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationEventConsumer {

    private final CompensationEventHandler compensationEventHandler;
    private final ObjectMapper objectMapper;

    /**
     * 보상 이벤트 소비
     * animal-service에서 실패 시 전송되는 보상 이벤트 처리
     */
    @KafkaListener(topics = "user.compensation.events", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeCompensationEvent(String message, Acknowledgment acknowledgment) {
        try {
            // JSON 메시지 파싱
            FavoriteCompensationEvent event = objectMapper.readValue(message, FavoriteCompensationEvent.class);

            log.info("[COMPENSATION] Received event: eventId={}, type={}, userId={}, animalId={}",
                    event.getEventId(), event.getCompensationType(), event.getUserId(), event.getAnimalId());

            // 보상 타입에 따라 처리
            switch (event.getCompensationType()) {
                case "ROLLBACK_FAVORITE_ADDED":
                    compensationEventHandler.rollbackFavoriteAdded(event);
                    break;
                case "ROLLBACK_FAVORITE_REMOVED":
                    compensationEventHandler.rollbackFavoriteRemoved(event);
                    break;
                default:
                    log.warn("[COMPENSATION] Unknown compensation type: {}", event.getCompensationType());
            }

            // 수동 커밋
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[COMPENSATION] Failed to process compensation event: {}", e.getMessage());
            // 실패 시 재시도하지 않고 로그만 남김 (데드레터 큐로 이동하도록 설정 가능)
            acknowledgment.acknowledge();
        }
    }
}
