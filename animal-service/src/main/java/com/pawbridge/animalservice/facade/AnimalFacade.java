package com.pawbridge.animalservice.facade;

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
    public int updateExpiredNoticeAnimals() {
        return commandService.updateExpiredNoticeAnimals();
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
     * 축종별 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findBySpecies(Species species, Pageable pageable) {
        return queryService.findBySpecies(species, pageable);
    }

    /**
     * 상태별 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByStatus(AnimalStatus status, Pageable pageable) {
        return queryService.findByStatus(status, pageable);
    }

    /**
     * 축종 + 성별 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findBySpeciesAndGender(Species species, Gender gender, Pageable pageable) {
        return queryService.findBySpeciesAndGender(species, gender, pageable);
    }

    /**
     * 축종 + 상태 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findBySpeciesAndStatus(Species species, AnimalStatus status, Pageable pageable) {
        return queryService.findBySpeciesAndStatus(species, status, pageable);
    }

    /**
     * 여러 상태 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByStatusIn(List<AnimalStatus> statuses, Pageable pageable) {
        return queryService.findByStatusIn(statuses, pageable);
    }

    /**
     * 공고 중인 동물 (공고 종료일 임박 순)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findNoticeAnimalsOrderByNoticeEndDate(Pageable pageable) {
        return queryService.findNoticeAnimalsOrderByNoticeEndDate(pageable);
    }

    /**
     * 공고 종료 임박 동물 (D-3 이내)
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        return queryService.findExpiringSoonAnimals(pageable);
    }

    /**
     * 공고 종료일 범위 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByNoticeEndDateBetween(LocalDate startDate, LocalDate endDate, Pageable pageable) {
        return queryService.findByNoticeEndDateBetween(startDate, endDate, pageable);
    }

    /**
     * 출생 연도 범위 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByBirthYearBetween(Integer startYear, Integer endYear, Pageable pageable) {
        return queryService.findByBirthYearBetween(startYear, endYear, pageable);
    }

    /**
     * 축종 + 출생 연도 범위 조회
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findBySpeciesAndBirthYearBetween(
            Species species, Integer startYear, Integer endYear, Pageable pageable) {
        return queryService.findBySpeciesAndBirthYearBetween(species, startYear, endYear, pageable);
    }

    /**
     * 품종명 검색
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchByBreed(String breed, Pageable pageable) {
        return queryService.searchByBreed(breed, pageable);
    }

    /**
     * 특징 검색
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchBySpecialMark(String keyword, Pageable pageable) {
        return queryService.searchBySpecialMark(keyword, pageable);
    }

    /**
     * 발견 장소 검색
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchByHappenPlace(String place, Pageable pageable) {
        return queryService.searchByHappenPlace(place, pageable);
    }

    /**
     * 축종 + 품종 검색
     */
    @Transactional(readOnly = true)
    public Page<AnimalResponse> searchBySpeciesAndBreed(Species species, String breed, Pageable pageable) {
        return queryService.searchBySpeciesAndBreed(species, breed, pageable);
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
}
