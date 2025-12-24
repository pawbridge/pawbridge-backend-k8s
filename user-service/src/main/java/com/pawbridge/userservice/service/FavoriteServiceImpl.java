package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.response.AnimalResponse;
import com.pawbridge.userservice.dto.response.FavoriteListResponseDto;
import com.pawbridge.userservice.dto.response.FavoriteResponseDto;
import com.pawbridge.userservice.dto.response.FavoriteWithAnimalDto;
import com.pawbridge.userservice.entity.Favorite;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.event.FavoriteAddedEvent;
import com.pawbridge.userservice.event.FavoriteRemovedEvent;
import com.pawbridge.userservice.exception.FavoriteAlreadyExistsException;
import com.pawbridge.userservice.exception.FavoriteNotFoundException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.repository.FavoriteRepository;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final OutboxService outboxService;
    private final AnimalServiceClient animalServiceClient;  // 추가

    /**
     * 찜 추가
     */
    @Override
    @Transactional
    public FavoriteResponseDto addFavorite(Long userId, Long animalId) {
        try {
            // 1. 중복 확인
            if (favoriteRepository.existsByUserUserIdAndAnimalId(userId, animalId)) {
                log.warn("Favorite already exists: userId={}, animalId={}", userId, animalId);
                throw new FavoriteAlreadyExistsException();
            }

            // 2. User 조회 (FK 제약조건 위함)
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> {
                        log.error("User not found: userId={}", userId);
                        return new UserNotFoundException();
                    });

            // 3. Favorite 저장
            Favorite favorite = Favorite.of(user, animalId);
            Favorite saved = favoriteRepository.save(favorite);

            // 4. Outbox 이벤트 발행
            String eventId = UUID.randomUUID().toString();
            Map<String, Object> eventPayload = Map.of(
                    "eventType", "FAVORITE_ADDED",
                    "eventId", eventId,
                    "userId", userId,
                    "animalId", animalId,
                    "timestamp", LocalDateTime.now().toString()
            );
            outboxService.saveEvent("Favorite", userId.toString(), "FAVORITE_ADDED", "user.favorite.events", eventPayload);

            return FavoriteResponseDto.fromEntity(saved);

        } catch (DataIntegrityViolationException e) {
            // Race condition - UNIQUE 제약 조건 위반
            log.warn("Race condition detected: userId={}, animalId={}", userId, animalId);
            throw new FavoriteAlreadyExistsException();
        }
    }

    /**
     * 찜 제거
     */
    @Override
    @Transactional
    public void removeFavorite(Long userId, Long animalId) {
        // 1. 존재 여부 확인
        if (!favoriteRepository.existsByUserUserIdAndAnimalId(userId, animalId)) {
            log.warn("Favorite not found: userId={}, animalId={}", userId, animalId);
            throw new FavoriteNotFoundException();
        }

        // 2. Favorite 삭제
        int deletedCount = favoriteRepository.deleteByUserUserIdAndAnimalId(userId, animalId);

        // 3. Outbox 이벤트 발행 (삭제 성공 시)
        if (deletedCount > 0) {
            String eventId = UUID.randomUUID().toString();
            Map<String, Object> eventPayload = Map.of(
                    "eventType", "FAVORITE_REMOVED",
                    "eventId", eventId,
                    "userId", userId,
                    "animalId", animalId,
                    "timestamp", LocalDateTime.now().toString()
            );
            outboxService.saveEvent("Favorite", userId.toString(), "FAVORITE_REMOVED", "user.favorite.events", eventPayload);
        }
    }

    /**
     * 찜 목록 조회
     */
    @Override
    @Transactional(readOnly = true)
    public FavoriteListResponseDto getFavorites(Long userId) {
        // 1. User 존재 여부 확인
        if (!userRepository.existsById(userId)) {
            log.error("User not found: userId={}", userId);
            throw new UserNotFoundException();
        }

        // 2. 사용자의 Favorite 목록 조회 (최신순)
        List<Favorite> favorites = favoriteRepository.findAllByUserUserIdOrderByCreatedAtDesc(userId);

        if (favorites.isEmpty()) {
            return FavoriteListResponseDto.of(userId, new ArrayList<>());
        }

        // 3. animalId 목록 추출
        List<Long> animalIds = favorites.stream()
                .map(Favorite::getAnimalId)
                .collect(Collectors.toList());

        // 4. FeignClient로 animal-service에서 동물 정보 일괄 조회
        List<AnimalResponse> animals = new ArrayList<>();
        try {
            animals = animalServiceClient.getAnimalsByIds(animalIds);
            log.info("Fetched {} animals from animal-service for user {}", animals.size(), userId);
        } catch (Exception e) {
            log.error("Failed to fetch animals from animal-service for user {}", userId, e);
            // Circuit Breaker 패턴: 실패 시에도 찜 목록은 반환 (동물 정보 없이)
            // 또는 예외를 던져서 프론트엔드에 에러 전달
        }

        // 5. animalId를 키로 하는 Map 생성 (O(1) 조회)
        Map<Long, AnimalResponse> animalMap = animals.stream()
                .collect(Collectors.toMap(AnimalResponse::getId, Function.identity()));

        // 6. Favorite + AnimalResponse 조합
        List<FavoriteWithAnimalDto> favoriteDtos = favorites.stream()
                .map(favorite -> {
                    AnimalResponse animal = animalMap.get(favorite.getAnimalId());
                    if (animal != null) {
                        return FavoriteWithAnimalDto.of(favorite, animal);
                    } else {
                        // 동물 정보가 없는 경우 (삭제되었거나 조회 실패)
                        log.warn("Animal not found for favoriteId={}, animalId={}",
                                favorite.getFavoriteId(), favorite.getAnimalId());
                        return FavoriteWithAnimalDto.ofWithoutAnimal(favorite);
                    }
                })
                .collect(Collectors.toList());

        // 7. 응답 반환
        return FavoriteListResponseDto.of(userId, favoriteDtos);
    }

    /**
     * 찜 여부 확인
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long animalId) {
        return favoriteRepository.existsByUserUserIdAndAnimalId(userId, animalId);
    }
}
