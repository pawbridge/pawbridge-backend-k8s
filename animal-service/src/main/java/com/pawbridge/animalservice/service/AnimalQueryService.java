package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.mapper.AnimalMapper;
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
 * - Response DTO 반환
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AnimalQueryService {

    private final AnimalRepository animalRepository;
    private final AnimalMapper mapper;

    /**
     * ID로 동물 상세 조회
     * @param id 동물 ID
     * @return AnimalDetailResponse
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public AnimalDetailResponse findById(Long id) {
        Animal animal = animalRepository.findWithShelterById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));
        return mapper.toDetailResponse(animal);
    }

    /**
     * APMS 유기번호로 상세 조회
     * @param apmsDesertionNo APMS 유기번호
     * @return AnimalDetailResponse
     * @throws EntityNotFoundException 동물이 없을 때
     */
    public AnimalDetailResponse findByApmsDesertionNo(String apmsDesertionNo) {
        Animal animal = animalRepository.findWithShelterByApmsDesertionNo(apmsDesertionNo)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + apmsDesertionNo));
        return mapper.toDetailResponse(animal);
    }

    /**
     * 축종별 조회
     */
    public Page<AnimalResponse> findBySpecies(Species species, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpecies(species, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 상태별 조회
     */
    public Page<AnimalResponse> findByStatus(AnimalStatus status, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByStatus(status, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 축종 + 성별 조회
     */
    public Page<AnimalResponse> findBySpeciesAndGender(Species species, Gender gender, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpeciesAndGender(species, gender, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 축종 + 상태 조회
     */
    public Page<AnimalResponse> findBySpeciesAndStatus(Species species, AnimalStatus status, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpeciesAndStatusWithShelter(species, status, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 여러 상태 조회
     */
    public Page<AnimalResponse> findByStatusIn(List<AnimalStatus> statuses, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByStatusIn(statuses, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 공고 중인 동물 (공고 종료일 임박 순)
     * - 메인 페이지용
     */
    public Page<AnimalResponse> findNoticeAnimalsOrderByNoticeEndDate(Pageable pageable) {
        Page<Animal> animals = animalRepository.findNoticeAnimalsOrderByNoticeEndDate(pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 공고 종료 임박 동물 (D-3 이내)
     */
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(3);
        Page<Animal> animals = animalRepository.findExpiringSoonAnimals(today, deadline, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 공고 종료일 범위 조회
     */
    public Page<AnimalResponse> findByNoticeEndDateBetween(LocalDate startDate, LocalDate endDate, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByNoticeEndDateBetween(startDate, endDate, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 출생 연도 범위 조회
     */
    public Page<AnimalResponse> findByBirthYearBetween(Integer startYear, Integer endYear, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByBirthYearBetween(startYear, endYear, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 축종 + 출생 연도 범위 조회
     */
    public Page<AnimalResponse> findBySpeciesAndBirthYearBetween(
            Species species, Integer startYear, Integer endYear, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpeciesAndBirthYearBetween(species, startYear, endYear, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 품종명 검색
     */
    public Page<AnimalResponse> searchByBreed(String breed, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByBreedContaining(breed, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 특징 검색
     */
    public Page<AnimalResponse> searchBySpecialMark(String keyword, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpecialMarkContaining(keyword, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 발견 장소 검색
     */
    public Page<AnimalResponse> searchByHappenPlace(String place, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByHappenPlaceContaining(place, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 축종 + 품종 검색
     */
    public Page<AnimalResponse> searchBySpeciesAndBreed(Species species, String breed, Pageable pageable) {
        Page<Animal> animals = animalRepository.findBySpeciesAndBreedContaining(species, breed, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 보호소 ID로 동물 목록 조회
     */
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByShelterId(shelterId, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 보호소 ID + 축종 조회
     */
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByShelterIdAndSpecies(shelterId, species, pageable);
        return animals.map(mapper::toResponse);
    }

    /**
     * 보호소 ID + 상태 조회
     */
    public Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable) {
        Page<Animal> animals = animalRepository.findByShelterIdAndStatus(shelterId, status, pageable);
        return animals.map(mapper::toResponse);
    }

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

    /**
     * APMS 수정 이후 동물 조회
     * - 증분 업데이트용
     */
    public List<Animal> findByApmsUpdatedAtAfter(LocalDateTime dateTime) {
        return animalRepository.findByApmsUpdatedAtAfter(dateTime);
    }
}
