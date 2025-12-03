package com.pawbridge.animalservice.facade;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.service.AnimalCommandService;
import com.pawbridge.animalservice.service.AnimalElasticsearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Animal Facade
 * - CQRS 패턴: Command (CUD) / Query (R) 분리
 * - Controller의 단일 진입점
 * - DTO 기반 처리
 *
 * <전략>
 * - Command (쓰기): MySQL (AnimalCommandService)
 * - Query (읽기): Elasticsearch (AnimalElasticsearchService) - Elasticsearch 올인 전략
 */
@Service
@RequiredArgsConstructor
public class AnimalFacade {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Dependencies
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private final AnimalCommandService commandService;              // Command: MySQL (CUD)
    private final AnimalElasticsearchService elasticsearchService;  // Query: Elasticsearch (R)

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
    // Query (읽기 - Elasticsearch)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ID로 동물 상세 조회 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public AnimalDetailResponse findById(Long id) {
        return elasticsearchService.findById(id);
    }

    /**
     * APMS 유기번호로 상세 조회 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public AnimalDetailResponse findByApmsDesertionNo(String apmsDesertionNo) {
        return elasticsearchService.findByApmsDesertionNo(apmsDesertionNo);
    }

    /**
     * 공고 종료 임박 동물 조회 (Elasticsearch)
     * - D-3 이내, 공고 종료일 오름차순
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        return elasticsearchService.findExpiringSoonAnimals(pageable);
    }

    /**
     * 보호소별 동물 목록 조회 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        return elasticsearchService.findByShelterId(shelterId, pageable);
    }

    /**
     * 보호소 + 축종별 동물 목록 조회 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        return elasticsearchService.findByShelterIdAndSpecies(shelterId, species, pageable);
    }

    /**
     * 보호소 + 상태별 동물 목록 조회 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        return elasticsearchService.findByShelterIdAndStatus(shelterId, status, pageable);
    }

    /**
     * 축종별 카운트 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public long countBySpecies(Species species) {
        return elasticsearchService.countBySpecies(species);
    }

    /**
     * 상태별 카운트 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public long countByStatus(AnimalStatus status) {
        return elasticsearchService.countByStatus(status);
    }

    /**
     * 보호소별 카운트 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public long countByShelterId(Long shelterId) {
        return elasticsearchService.countByShelterId(shelterId);
    }

    /**
     * 축종 + 상태별 카운트 (Elasticsearch)
     */
    @Transactional(readOnly = true)
    public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        return elasticsearchService.countBySpeciesAndStatus(species, status);
    }

    /**
     * 통합 검색 (Elasticsearch)
     * - 복합 검색 조건, 키워드 검색, 형태소 분석
     * - 페이징 및 정렬
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        return elasticsearchService.searchAnimals(request, pageable);
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
