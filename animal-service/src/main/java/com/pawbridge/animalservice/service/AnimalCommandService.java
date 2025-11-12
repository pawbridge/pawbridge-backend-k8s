package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.AnimalStatus;
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
 */
@Service
@RequiredArgsConstructor
public class AnimalCommandService {

    private final AnimalRepository animalRepository;
    private final ShelterRepository shelterRepository;

    // ========== 생성 ==========

    /**
     * 동물 생성 (보호소 직접 등록)
     * - Shelter는 이미 존재해야 함
     * @param animal 동물 정보
     * @return 저장된 Animal
     * @throws EntityNotFoundException Shelter가 없을 때
     */
    @Transactional
    public Animal create(Animal animal) {
        // Shelter 존재 확인
        Shelter shelter = shelterRepository.findById(animal.getShelter().getId())
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + animal.getShelter().getId()));

        animal.setShelter(shelter);
        return animalRepository.save(animal);
    }

    // ========== 수정 ==========

    /**
     * 동물 상태 변경
     * @param id 동물 ID
     * @param newStatus 새로운 상태
     * @return 수정된 Animal
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public Animal updateStatus(Long id, AnimalStatus newStatus) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.updateStatus(newStatus);  // Entity 메서드 활용
        return animal;  // dirty checking
    }

    /**
     * 보호소 설명 수정
     * @param id 동물 ID
     * @param description 설명
     * @return 수정된 Animal
     * @throws EntityNotFoundException 동물이 없을 때
     */
    @Transactional
    public Animal updateDescription(Long id, String description) {
        Animal animal = animalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));

        animal.setDescription(description);
        return animal;
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

    // ========== 삭제 ==========

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

    // ========== 배치 작업용 ==========

    /**
     * APMS 데이터로부터 동물 생성 (배치 전용)
     * - 중복 체크 포함
     * @param animal 동물 정보
     * @return 저장된 Animal
     * @throws IllegalStateException 이미 존재하는 동물일 때
     */
    @Transactional
    public Animal createFromApms(Animal animal) {
        // 중복 체크
        if (animalRepository.existsByApmsDesertionNo(animal.getApmsDesertionNo())) {
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
