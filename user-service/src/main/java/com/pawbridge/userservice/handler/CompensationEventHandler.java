package com.pawbridge.userservice.handler;

import com.pawbridge.userservice.entity.ProcessedEvent;
import com.pawbridge.userservice.event.FavoriteCompensationEvent;
import com.pawbridge.userservice.repository.FavoriteRepository;
import com.pawbridge.userservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationEventHandler {

    private final FavoriteRepository favoriteRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * FAVORITE_ADDED 이벤트 롤백 처리
     * animal-service에서 실패 시 user-service의 찜 데이터 삭제
     */
    @Transactional
    public void rollbackFavoriteAdded(FavoriteCompensationEvent event) {
        String eventId = event.getEventId();
        String originalEventId = event.getOriginalEventId();
        Long userId = event.getUserId();
        Long animalId = event.getAnimalId();

        // 1. Idempotency check
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("[COMPENSATION] Duplicate event skipped: eventId={}", eventId);
            return;
        }

        // 2. ProcessedEvent 먼저 저장 (Race condition 방지)
        try {
            processedEventRepository.save(ProcessedEvent.of(eventId, "ROLLBACK_FAVORITE_ADDED"));
        } catch (Exception e) {
            // 이미 처리된 경우 (동시성)
            log.warn("[COMPENSATION] Event already processed: eventId={}", eventId);
            return;
        }

        // 3. Favorite 삭제
        int deletedCount = favoriteRepository.deleteByUserUserIdAndAnimalId(userId, animalId);

        if (deletedCount == 0) {
            log.warn("[COMPENSATION] Favorite already removed: eventId={}, userId={}, animalId={}",
                    eventId, userId, animalId);
        }
    }

    /**
     * FAVORITE_REMOVED 이벤트 롤백 처리
     * animal-service에서 실패 시 user-service의 찜 데이터 복원
     * (현재 설계에서는 미사용 - 향후 확장 가능)
     */
    @Transactional
    public void rollbackFavoriteRemoved(FavoriteCompensationEvent event) {
        String eventId = event.getEventId();

        // 1. Idempotency check
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("[COMPENSATION] Duplicate event skipped: eventId={}", eventId);
            return;
        }

        // 2. ProcessedEvent 먼저 저장
        try {
            processedEventRepository.save(ProcessedEvent.of(eventId, "ROLLBACK_FAVORITE_REMOVED"));
        } catch (Exception e) {
            log.warn("[COMPENSATION] Event already processed: eventId={}", eventId);
            return;
        }

        // Note: 삭제 롤백은 복잡하므로 현재는 로그만 남김
        log.warn("[COMPENSATION] Favorite removal rollback requested but not implemented: eventId={}", eventId);
    }
}
