package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.specification.AnimalSpecification;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
     * 공고 종료 임박 동물 (D-3 이내)
     */
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(3);
        Page<Animal> animals = animalRepository.findExpiringSoonAnimals(today, deadline, pageable);
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

    /**
     * 동물 통합 검색 (동적 쿼리)
     * - Phase 1: MySQL Specification 기반
     * - Phase 4: OpenSearch로 전환 예정
     *
     * @param request 검색 조건
     * @param pageable 페이징 정보
     * @return Page<AnimalResponse>
     */
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        // 검색 조건 Specification
        Specification<Animal> spec = AnimalSpecification.searchAnimals(request);

        // Shelter fetch join 추가 (N+1 방지)
        spec = spec.and(AnimalSpecification.fetchShelter());

        // 검색 실행
        Page<Animal> animals = animalRepository.findAll(spec, pageable);

        // DTO 변환
        return animals.map(mapper::toResponse);
    }
}
