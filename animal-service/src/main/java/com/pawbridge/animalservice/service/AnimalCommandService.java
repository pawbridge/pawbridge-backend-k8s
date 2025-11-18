package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.ShelterRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Animal Command Service (CUD)
 * - 동물 생성, 수정, 삭제 담당
 * - 쓰기 작업 트랜잭션 관리
 * - DTO 기반 처리
 */
@Service
@RequiredArgsConstructor
public class AnimalCommandService {

    private final AnimalRepository animalRepository;
    private final ShelterRepository shelterRepository;
    private final AnimalMapper mapper;

    /**
     * 동물 생성 (보호소 직접 등록)
     * @param request 동물 등록 요청 DTO
     * @return AnimalDetailResponse
     * @throws EntityNotFoundException Shelter가 없을 때
     */
    @Transactional
    public AnimalDetailResponse create(CreateAnimalRequest request) {
        // Shelter 조회
        Shelter shelter = shelterRepository.findById(request.getShelterId())
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + request.getShelterId()));

        // DTO → Entity 변환
        Animal animal = mapper.toEntity(request, shelter);

        // 저장
        Animal saved = animalRepository.save(animal);

        // Entity → Response DTO
        return mapper.toDetailResponse(saved);
    }

    /**
     * 동물 상태 변경
     * @param id 동물 ID
     * @param request 상태 변경 요청 DTO
     * @return AnimalDetailResponse
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public AnimalDetailResponse updateStatus(Long id, UpdateAnimalStatusRequest request) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.updateStatus(request.getNewStatus());

        return mapper.toDetailResponse(animal);
    }

    /**
     * 보호소 설명 수정
     * @param id 동물 ID
     * @param request 설명 수정 요청 DTO
     * @return AnimalDetailResponse
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public AnimalDetailResponse updateDescription(Long id, UpdateAnimalDescriptionRequest request) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.updateDescription(request.getDescription());

        return mapper.toDetailResponse(animal);
    }

    /**
     * 찜 횟수 증가
     * - user-service에서 Kafka 이벤트 받아 호출
     * @param id 동물 ID
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public void incrementFavoriteCount(Long id) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.incrementFavoriteCount();
    }

    /**
     * 찜 횟수 감소
     * @param id 동물 ID
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public void decrementFavoriteCount(Long id) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.decrementFavoriteCount();
    }

    /**
     * 동물 삭제
     * @param id 동물 ID
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public void delete(Long id) {
        if (!animalRepository.existsById(id)) {
            throw new EntityNotFoundException("Animal not found: " + id);
        }
        animalRepository.deleteById(id);
    }

    /**
     * APMS 데이터로부터 동물 생성 (배치 전용)
     * - 중복 체크 포함
     * - ApmsDataMapper에서 Entity를 만들어서 전달
     * @param animal 동물 Entity
     * @return 저장된 Animal
     * @throws IllegalStateException 이미 존재하는 동물일 때
     */
    @Transactional
    public Animal createFromApms(Animal animal) {
        // 중복 체크
        if (animal.getApmsDesertionNo() != null &&
                animalRepository.existsByApmsDesertionNo(animal.getApmsDesertionNo())) {
            throw new IllegalStateException("Animal already exists: " + animal.getApmsDesertionNo());
        }

        return animalRepository.save(animal);
    }

    /**
     * 공고 종료된 동물 상태 일괄 업데이트
     * - 배치 작업용: NOTICE → PROTECT
     * @return 업데이트된 개수
     */
    @Transactional
    public int updateExpiredNoticeAnimals() {
        List<Animal> expiredAnimals = animalRepository.findExpiredNoticeAnimals(LocalDate.now());

        expiredAnimals.forEach(animal -> animal.updateStatus(AnimalStatus.PROTECT));

        return expiredAnimals.size();
    }
}
