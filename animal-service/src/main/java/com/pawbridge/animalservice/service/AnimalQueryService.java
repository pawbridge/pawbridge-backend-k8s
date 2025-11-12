package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.repository.AnimalRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Animal Query Service (R)
 * - 동물 조회, 검색 담당
 * - 읽기 전용 트랜잭션
 */
@Service
@Transactional(readOnly = true)  // 클래스 레벨: 읽기 전용
@RequiredArgsConstructor
public class AnimalQueryService {

    private final AnimalRepository animalRepository;

    // ========== 단건 조회 ==========

    /**
     * ID로 동물 조회 (Shelter 없음)
     * @param id 동물 ID
     * @return Animal
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public Animal findById(Long id) {
        return animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));
    }

    /**
     * ID로 동물 조회 (Shelter 포함)
     * - @EntityGraph로 N+1 방지
     * @param id 동물 ID
     * @return Animal with Shelter
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public Animal findByIdWithShelter(Long id) {
        return animalRepository.findWithShelterById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));
    }

    /**
     * APMS 유기번호로 조회
     * @param apmsDesertionNo APMS 유기번호
     * @return Animal
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public Animal findByApmsDesertionNo(String apmsDesertionNo) {
        return animalRepository.findByApmsDesertionNo(apmsDesertionNo)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + apmsDesertionNo));
    }

    /**
     * APMS 유기번호로 조회 (Shelter 포함)
     * @param apmsDesertionNo APMS 유기번호
     * @return Animal with Shelter
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public Animal findByApmsDesertionNoWithShelter(String apmsDesertionNo) {
        return animalRepository.findWithShelterByApmsDesertionNo(apmsDesertionNo)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + apmsDesertionNo));
    }

    // ========== 목록 조회 (기본) ==========

    /**
     * 축종별 조회
     */
    public Page<Animal> findBySpecies(Species species, Pageable pageable) {
        return animalRepository.findBySpecies(species, pageable);
    }

    /**
     * 상태별 조회
     */
    public Page<Animal> findByStatus(AnimalStatus status, Pageable pageable) {
        return animalRepository.findByStatus(status, pageable);
    }

    /**
     * 축종 + 성별 조회
     */
    public Page<Animal> findBySpeciesAndGender(Species species, Gender gender, Pageable pageable) {
        return animalRepository.findBySpeciesAndGender(species, gender, pageable);
    }

    /**
     * 축종 + 상태 조회
     */
    public Page<Animal> findBySpeciesAndStatus(Species species, AnimalStatus status, Pageable pageable) {
        return animalRepository.findBySpeciesAndStatus(species, status, pageable);
    }

    /**
     * 여러 상태 조회
     */
    public Page<Animal> findByStatusIn(List<AnimalStatus> statuses, Pageable pageable) {
        return animalRepository.findByStatusIn(statuses, pageable);
    }

    /**
     * 축종 + 상태 조회 (Shelter 포함)
     */
    public Page<Animal> findBySpeciesAndStatusWithShelter(Species species, AnimalStatus status, Pageable pageable) {
        return animalRepository.findBySpeciesAndStatusWithShelter(species, status, pageable);
    }

    // ========== 공고 관련 조회 ==========

    /**
     * 공고 중인 동물 (공고 종료일 임박 순)
     * - 메인 페이지용
     */
    public Page<Animal> findNoticeAnimalsOrderByNoticeEndDate(Pageable pageable) {
        return animalRepository.findNoticeAnimalsOrderByNoticeEndDate(pageable);
    }

    /**
     * 공고 종료 임박 동물 (D-3 이내)
     */
    public Page<Animal> findExpiringSoonAnimals(Pageable pageable) {
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(3);
        return animalRepository.findExpiringSoonAnimals(today, deadline, pageable);
    }

    /**
     * 공고 종료일 범위 조회
     */
    public Page<Animal> findByNoticeEndDateBetween(LocalDate startDate, LocalDate endDate, Pageable pageable) {
        return animalRepository.findByNoticeEndDateBetween(startDate, endDate, pageable);
    }

    // ========== 나이 범위 검색 ==========

    /**
     * 출생 연도 범위 조회
     */
    public Page<Animal> findByBirthYearBetween(Integer startYear, Integer endYear, Pageable pageable) {
        return animalRepository.findByBirthYearBetween(startYear, endYear, pageable);
    }

    /**
     * 축종 + 출생 연도 범위 조회
     */
    public Page<Animal> findBySpeciesAndBirthYearBetween(
            Species species, Integer startYear, Integer endYear, Pageable pageable) {
        return animalRepository.findBySpeciesAndBirthYearBetween(species, startYear, endYear, pageable);
    }

    // ========== 텍스트 검색 ==========

    /**
     * 품종명 검색
     */
    public Page<Animal> searchByBreed(String breed, Pageable pageable) {
        return animalRepository.findByBreedContaining(breed, pageable);
    }

    /**
     * 특징 검색
     */
    public Page<Animal> searchBySpecialMark(String keyword, Pageable pageable) {
        return animalRepository.findBySpecialMarkContaining(keyword, pageable);
    }

    /**
     * 발견 장소 검색
     */
    public Page<Animal> searchByHappenPlace(String place, Pageable pageable) {
        return animalRepository.findByHappenPlaceContaining(place, pageable);
    }

    /**
     * 축종 + 품종 검색
     */
    public Page<Animal> searchBySpeciesAndBreed(Species species, String breed, Pageable pageable) {
        return animalRepository.findBySpeciesAndBreedContaining(species, breed, pageable);
    }

    // ========== 보호소별 조회 ==========

    /**
     * 보호소 ID로 동물 목록 조회
     */
    public Page<Animal> findByShelterId(Long shelterId, Pageable pageable) {
        return animalRepository.findByShelterId(shelterId, pageable);
    }

    /**
     * 보호소 ID + 축종 조회
     */
    public Page<Animal> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        return animalRepository.findByShelterIdAndSpecies(shelterId, species, pageable);
    }

    /**
     * 보호소 ID + 상태 조회
     */
    public Page<Animal> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        return animalRepository.findByShelterIdAndStatus(shelterId, status, pageable);
    }

    // ========== 통계 ==========

    /**
     * 축종별 카운트
     */
    public long countBySpecies(Species species) {
        return animalRepository.countBySpecies(species);
    }

    /**
     * 상태별 카운트
     */
    public long countByStatus(AnimalStatus status) {
        return animalRepository.countByStatus(status);
    }

    /**
     * 보호소별 카운트
     */
    public long countByShelterId(Long shelterId) {
        return animalRepository.countByShelterId(shelterId);
    }

    /**
     * 축종 + 상태별 카운트
     */
    public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        return animalRepository.countBySpeciesAndStatus(species, status);
    }

    // ========== 배치 작업용 ==========

    /**
     * APMS 수정 이후 동물 조회
     * - 증분 업데이트용
     */
    public List<Animal> findByApmsUpdatedAtAfter(LocalDateTime dateTime) {
        return animalRepository.findByApmsUpdatedAtAfter(dateTime);
    }
}
