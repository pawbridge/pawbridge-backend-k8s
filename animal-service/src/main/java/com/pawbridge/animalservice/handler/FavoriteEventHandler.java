package com.pawbridge.animalservice.handler;

import com.pawbridge.animalservice.entity.ProcessedEvent;
import com.pawbridge.animalservice.repository.ProcessedEventRepository;
import com.pawbridge.animalservice.service.AnimalCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Favorite 이벤트 비즈니스 로직 처리 (Delegator 패턴)
 *
 * 역할:
 * - Kafka를 모르는 순수 비즈니스 로직
 * - @Transactional로 멱등성 체크 + 비즈니스 로직 원자성 보장
 * - 예외 발생 시 throw → DefaultErrorHandler가 재시도
 *
 * 메서드 이름:
 * - handleXxx (X) - Kafka 용어
 * - addFavorite, removeFavorite (O) - 비즈니스 의미
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FavoriteEventHandler {

    private final AnimalCommandService animalCommandService;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * 찜 추가 처리
     *
     * @param eventId  이벤트 ID (멱등성 보장)
     * @param userId   사용자 ID
     * @param animalId 동물 ID
     * @throws Exception 처리 실패 시 (DefaultErrorHandler가 재시도)
     */
    @Transactional
    public void addFavorite(String eventId, Long userId, Long animalId) {
        log.info("[HANDLER] Adding favorite: eventId={}, userId={}, animalId={}",
                eventId, userId, animalId);

        // 1. 멱등성 체크 (@Transactional 내부에서 체크 → Race Condition 방지)
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("[HANDLER] Duplicate event, skipping: eventId={}", eventId);
            return;  // 중복은 정상 처리로 간주
        }

        // 2. 비즈니스 로직 실행
        //    예외 발생 시 트랜잭션 롤백 + throw → DefaultErrorHandler 재시도
        animalCommandService.incrementFavoriteCount(animalId);

        // 3. 처리 완료 기록 (멱등성)
        processedEventRepository.save(ProcessedEvent.of(eventId, "FAVORITE_ADDED"));

        log.info("[HANDLER] Successfully added favorite: eventId={}, animalId={}",
                eventId, animalId);
    }

    /**
     * 찜 제거 처리
     *
     * @param eventId  이벤트 ID (멱등성 보장)
     * @param userId   사용자 ID
     * @param animalId 동물 ID
     * @throws Exception 처리 실패 시 (DefaultErrorHandler가 재시도)
     */
    @Transactional
    public void removeFavorite(String eventId, Long userId, Long animalId) {
        log.info("[HANDLER] Removing favorite: eventId={}, userId={}, animalId={}",
                eventId, userId, animalId);

        // 1. 멱등성 체크
        if (processedEventRepository.existsByEventId(eventId)) {
            log.warn("[HANDLER] Duplicate event, skipping: eventId={}", eventId);
            return;
        }

        // 2. 비즈니스 로직 실행
        animalCommandService.decrementFavoriteCount(animalId);

        // 3. 처리 완료 기록
        processedEventRepository.save(ProcessedEvent.of(eventId, "FAVORITE_REMOVED"));

        log.info("[HANDLER] Successfully removed favorite: eventId={}, animalId={}",
                eventId, animalId);
    }
}
