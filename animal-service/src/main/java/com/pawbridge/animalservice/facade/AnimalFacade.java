package com.pawbridge.animalservice.facade;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.service.AnimalCommandService;
import com.pawbridge.animalservice.service.AnimalQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Animal Facade
 * - Command + Query 통합
 * - Controller의 단일 진입점
 * - CQRS 내부 구조를 외부로부터 숨김
 * - DTO 기반 처리
 */
@Service
@RequiredArgsConstructor
public class AnimalFacade {

    private final AnimalCommandService commandService;
    private final AnimalQueryService queryService;

    /**
     * 동물 생성
     */
    @Transactional
    public AnimalDetailResponse create(CreateAnimalRequest request) {
        return commandService.create(request);
    }

    /**
     * 동물 상태 변경
     */
    @Transactional
    public AnimalDetailResponse updateStatus(Long id, UpdateAnimalStatusRequest request) {
        return commandService.updateStatus(id, request);
    }

    /**
     * 보호소 설명 수정
     */
    @Transactional
    public AnimalDetailResponse updateDescription(Long id, UpdateAnimalDescriptionRequest request) {
        return commandService.updateDescription(id, request);
    }

    /**
     * 찜 횟수 증가
     */
    @Transactional
    public void incrementFavoriteCount(Long id) {
        commandService.incrementFavoriteCount(id);
    }

    /**
     * 찜 횟수 감소
     */
    @Transactional
    public void decrementFavoriteCount(Long id) {
        commandService.decrementFavoriteCount(id);
    }

    /**
     * 동물 삭제
     */
    @Transactional
    public void delete(Long id) {
        commandService.delete(id);
    }

    /**
     * APMS 데이터로부터 동물 생성 (배치 전용)
     * - Entity를 직접 받음 (ApmsDataMapper에서 생성)
     */
    @Transactional
    public Animal createFromApms(Animal animal) {
        return commandService.createFromApms(animal);
    }

    /**
     * 공고 종료된 동물 상태 일괄 업데이트 (배치)
     */
    @Transactional
    public int updateExpiredProtectAnimals() {
        return commandService.updateExpiredProtectAnimals();
    }

    /**
     * ID로 동물 상세 조회
     */
    @Transactional(readOnly = true)
    public AnimalDetailResponse findById(Long id) {
        return queryService.findById(id);
    }

    /**
     * APMS 유기번호로 상세 조회
     */
    @Transactional(readOnly = true)
    public AnimalDetailResponse findByApmsDesertionNo(String apmsDesertionNo) {
        return queryService.findByApmsDesertionNo(apmsDesertionNo);
    }


    /**
     * 공고 종료 임박 동물 (D-3 이내)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        return queryService.findExpiringSoonAnimals(pageable);
    }


    /**
     * 보호소 ID로 동물 목록 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        return queryService.findByShelterId(shelterId, pageable);
    }

    /**
     * 보호소 ID + 축종 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        return queryService.findByShelterIdAndSpecies(shelterId, species, pageable);
    }

    /**
     * 보호소 ID + 상태 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        return queryService.findByShelterIdAndStatus(shelterId, status, pageable);
    }

    /**
     * 축종별 카운트
     */
    @Transactional(readOnly = true)
    public long countBySpecies(Species species) {
        return queryService.countBySpecies(species);
    }

    /**
     * 상태별 카운트
     */
    @Transactional(readOnly = true)
    public long countByStatus(AnimalStatus status) {
        return queryService.countByStatus(status);
    }

    /**
     * 보호소별 카운트
     */
    @Transactional(readOnly = true)
    public long countByShelterId(Long shelterId) {
        return queryService.countByShelterId(shelterId);
    }

    /**
     * 축종 + 상태별 카운트
     */
    @Transactional(readOnly = true)
    public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        return queryService.countBySpeciesAndStatus(species, status);
    }

    /**
     * APMS 수정 이후 동물 조회 (배치)
     * - Entity 반환 (배치 작업에서 사용)
     */
    @Transactional(readOnly = true)
    public List<Animal> findByApmsUpdatedAtAfter(LocalDateTime dateTime) {
        return queryService.findByApmsUpdatedAtAfter(dateTime);
    }

    /**
     * 동물 통합 검색 (동적 쿼리)
     * - Phase 1: MySQL Specification 기반
     * - Phase 4: OpenSearch로 전환 예정
     *
     * @param request 검색 조건
     * @param pageable 페이징 정보
     * @return Page<AnimalResponse>
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        return queryService.searchAnimals(request, pageable);
    }
}
