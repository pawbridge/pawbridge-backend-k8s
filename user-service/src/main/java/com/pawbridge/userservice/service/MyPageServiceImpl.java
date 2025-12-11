package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.client.StoreServiceClient;
import com.pawbridge.userservice.dto.response.AnimalResponse;
import com.pawbridge.userservice.dto.response.PageResponse;
import com.pawbridge.userservice.dto.response.ShelterResponse;
import com.pawbridge.userservice.dto.response.WishlistResponse;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.UnauthorizedException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 서비스 구현
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageServiceImpl implements MyPageService {

    private final UserRepository userRepository;
    private final AnimalServiceClient animalServiceClient;
    private final StoreServiceClient storeServiceClient;

    /**
     * 내가 등록한 동물 조회 (보호소 직원용)
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<AnimalResponse> getRegisteredAnimals(Long userId, Pageable pageable) {
        log.info("Fetching registered animals for user: {}", userId);

        // 1. User 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.error("User not found: userId={}", userId);
                    return new UserNotFoundException();
                });

        // 2. Role 검증 (보호소 직원만 가능)
        if (user.getRole() != Role.ROLE_SHELTER) {
            log.warn("Unauthorized access: userId={}, role={}", userId, user.getRole());
            throw new UnauthorizedException("보호소 직원만 조회할 수 있습니다.");
        }

        // 3. careRegNo 확인
        String careRegNo = user.getCareRegNo();
        if (careRegNo == null || careRegNo.isEmpty()) {
            log.error("CareRegNo is null for shelter user: userId={}", userId);
            throw new IllegalStateException("보호소 등록번호가 없습니다.");
        }

        // 4. FeignClient로 Shelter 조회
        ShelterResponse shelter;
        try {
            shelter = animalServiceClient.getShelterByCareRegNo(careRegNo);
            log.info("Found shelter: id={}, name={}", shelter.getId(), shelter.getName());
        } catch (Exception e) {
            log.error("Failed to fetch shelter from animal-service: careRegNo={}", careRegNo, e);
            throw new RuntimeException("보호소 정보를 조회할 수 없습니다.", e);
        }

        // 5. FeignClient로 보호소의 동물 목록 조회
        PageResponse<AnimalResponse> animals;
        try {
            // Sort를 "property,direction" 형식으로 변환
            String sortParam = "createdAt,desc"; // 기본값
            if (pageable.getSort().isSorted()) {
                sortParam = pageable.getSort().stream()
                        .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("createdAt,desc");
            }

            animals = animalServiceClient.getAnimalsByShelterId(
                    shelter.getId(),
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    sortParam
            );
            log.info("Found {} animals for shelter: {}", animals.getTotalElements(), shelter.getName());
        } catch (Exception e) {
            log.error("Failed to fetch animals from animal-service: shelterId={}", shelter.getId(), e);
            throw new RuntimeException("동물 목록을 조회할 수 없습니다.", e);
        }

        return animals;
    }

    /**
     * 내 찜 목록 조회
     */
    @Override
    @Transactional(readOnly = true)
    public Page<WishlistResponse> getWishlists(Long userId, Pageable pageable) {
        log.info("Fetching wishlists for user: {}", userId);

        // 1. User 존재 여부 확인
        if (!userRepository.existsById(userId)) {
            log.error("User not found: userId={}", userId);
            throw new UserNotFoundException();
        }

        // 2. FeignClient로 store-service에서 찜 목록 조회
        Page<WishlistResponse> wishlists;
        try {
            // Sort를 "property,direction" 형식으로 변환
            String sortParam = "createdAt,desc"; // 기본값
            if (pageable.getSort().isSorted()) {
                sortParam = pageable.getSort().stream()
                        .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("createdAt,desc");
            }

            wishlists = storeServiceClient.getWishlistsByUserId(
                    userId,
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    sortParam
            );
            log.info("Found {} wishlists for user: {}", wishlists.getTotalElements(), userId);
        } catch (Exception e) {
            log.error("Failed to fetch wishlists from store-service: userId={}", userId, e);
            throw new RuntimeException("찜 목록을 조회할 수 없습니다.", e);
        }

        return wishlists;
    }
}
