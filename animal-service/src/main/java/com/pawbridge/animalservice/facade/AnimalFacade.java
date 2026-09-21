package com.pawbridge.animalservice.facade;

import com.pawbridge.animalservice.service.AnimalRecommendationService;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.AnimalNotFoundException;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.service.AnimalCommandService;
import com.pawbridge.animalservice.service.AnimalQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Animal Facade
 * - CQRS 패턴: Command (CUD) / Query (R) 분리
 * - Controller의 단일 진입점
 * - DTO 기반 처리
 *
 * <전략>
 * - Command (쓰기): MySQL (AnimalCommandService)
 * - Query (읽기): AnimalQueryService - 설정으로 선택한 목록/검색 저장소
 * - 상세 조회: MySQL (성능보다 데이터 정합성 우선)
 */
@Service
@RequiredArgsConstructor
public class AnimalFacade {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Dependencies
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private final AnimalCommandService commandService;              // Command: MySQL (CUD)
    private final AnimalQueryService queryService;  // 목록/검색 backend가 읽기 트랜잭션을 관리
    private final AnimalRepository animalRepository;                // 상세 조회용 (MySQL)
    private final AnimalMapper animalMapper;                        // Entity → DTO 변환
    private final AnimalRecommendationService recommendationService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Command (쓰기 - MySQL)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 동물 생성 (MySQL)
     */
    @Transactional
    public AnimalDetailResponse create(CreateAnimalRequest request) {
        return commandService.create(request);
    }

    /**
     * 동물 상태 변경 (MySQL)
     */
    @Transactional
    public AnimalDetailResponse updateStatus(Long id, UpdateAnimalStatusRequest request) {
        return commandService.updateStatus(id, request);
    }

    /**
     * 보호소 설명 수정 (MySQL)
     */
    @Transactional
    public AnimalDetailResponse updateDescription(Long id, UpdateAnimalDescriptionRequest request) {
        return commandService.updateDescription(id, request);
    }

    /**
     * 찜 횟수 증가 (MySQL)
     */
    @Transactional
    public void incrementFavoriteCount(Long id) {
        commandService.incrementFavoriteCount(id);
    }

    /**
     * 찜 횟수 감소 (MySQL)
     */
    @Transactional
    public void decrementFavoriteCount(Long id) {
        commandService.decrementFavoriteCount(id);
    }

    /**
     * 동물 삭제 (MySQL)
     */
    @Transactional
    public void delete(Long id) {
        commandService.delete(id);
    }

    /**
     * APMS 데이터로부터 동물 생성 (배치 전용, MySQL)
     * - Entity를 직접 받음 (ApmsDataMapper에서 생성)
     */
    @Transactional
    public Animal createFromApms(Animal animal) {
        return commandService.createFromApms(animal);
    }

    /**
     * 공고 종료된 동물 상태 일괄 업데이트 (배치, MySQL)
     */
    @Transactional
    public int updateExpiredProtectAnimals() {
        return commandService.updateExpiredProtectAnimals();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Query (읽기 - 설정으로 선택한 검색 저장소)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ID로 동물 상세 조회 (MySQL)
     * - 상세 조회는 데이터 정합성을 위해 MySQL 사용
     * - Shelter 정보 fetch join으로 N+1 방지
     */
    @Transactional(readOnly = true)
    public AnimalDetailResponse findById(Long id) {
        Animal animal = animalRepository.findWithShelterById(id)
                .orElseThrow(AnimalNotFoundException::new);
        return animalMapper.toDetailResponse(animal);
    }

    /**
     * APMS 유기번호로 상세 조회 (선택한 검색 저장소)
     */
    public AnimalDetailResponse findByApmsDesertionNo(String apmsDesertionNo) {
        return queryService.findByApmsDesertionNo(apmsDesertionNo);
    }

    /**
     * 공고 종료 임박 동물 조회 (선택한 검색 저장소)
     * - D-3 이내, 공고 종료일 오름차순
     */
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        return queryService.findExpiringSoonAnimals(pageable);
    }

    /**
     * 보호소별 동물 목록 조회 (선택한 검색 저장소)
     */
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        return queryService.findByShelterId(shelterId, pageable);
    }

    /**
     * 보호소 + 축종별 동물 목록 조회 (선택한 검색 저장소)
     */
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        return queryService.findByShelterIdAndSpecies(shelterId, species, pageable);
    }

    /**
     * 보호소 + 상태별 동물 목록 조회 (선택한 검색 저장소)
     */
    public Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        return queryService.findByShelterIdAndStatus(shelterId, status, pageable);
    }

    /**
     * 축종별 카운트 (선택한 검색 저장소)
     */
    public long countBySpecies(Species species) {
        return queryService.countBySpecies(species);
    }

    /**
     * 상태별 카운트 (선택한 검색 저장소)
     */
    public long countByStatus(AnimalStatus status) {
        return queryService.countByStatus(status);
    }

    /**
     * 보호소별 카운트 (선택한 검색 저장소)
     */
    public long countByShelterId(Long shelterId) {
        return queryService.countByShelterId(shelterId);
    }

    /**
     * 축종 + 상태별 카운트 (선택한 검색 저장소)
     */
    public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        return queryService.countBySpeciesAndStatus(species, status);
    }

    /**
     * 통합 검색 (선택한 검색 저장소)
     * - 복합 조건과 키워드 관련도는 선택한 검색 구현에서 처리
     * - 페이징 및 정렬
     */
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        return queryService.searchAnimals(request, pageable);
    }

    /** 유사도 순서를 유지하면서 현재 공고·보호 상태를 최종 확인한다. */
    public List<AnimalResponse> getSimilarAnimals(Long id) {
        return recommendationService.recommend(id);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 배치 작업용 (MySQL 직접 접근 필요)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * APMS 수정 이후 동물 조회 (배치 전용, MySQL)
     * - Entity 반환 (배치 작업에서 사용)
     * - Elasticsearch가 아닌 MySQL에서 직접 조회 (트랜잭션 일관성)
     */
    @Transactional(readOnly = true)
    public List<Animal> findByApmsUpdatedAtAfter(LocalDateTime dateTime) {
        // 배치 작업은 MySQL에서 직접 조회 (트랜잭션 일관성 보장)
        // TODO: QueryService 대신 CommandService 또는 Repository로 변경 필요
        throw new UnsupportedOperationException("배치 작업용 메서드 - QueryService 제거 후 재구현 필요");
    }
}
