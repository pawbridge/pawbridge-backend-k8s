package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.repository.ShelterRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shelter Command Service (CUD)
 * - 보호소 생성, 수정, 삭제 담당
 * - 쓰기 작업 트랜잭션 관리
 */
@Service
@RequiredArgsConstructor
public class ShelterCommandService {

    private final ShelterRepository shelterRepository;

    // ========== 생성 ==========

    /**
     * 보호소 생성
     * @param shelter 보호소 정보
     * @return 저장된 Shelter
     */
    @Transactional
    public Shelter create(Shelter shelter) {
        return shelterRepository.save(shelter);
    }

    /**
     * APMS 데이터로부터 보호소 생성 (배치 전용)
     * - 중복 체크 포함
     * @param shelter 보호소 정보
     * @return 저장된 Shelter
     * @throws IllegalStateException 이미 존재하는 보호소일 때
     */
    @Transactional
    public Shelter createFromApms(Shelter shelter) {
        // 중복 체크
        if (shelterRepository.existsByCareRegNo(shelter.getCareRegNo())) {
            throw new IllegalStateException("Shelter already exists: " + shelter.getCareRegNo());
        }

        return shelterRepository.save(shelter);
    }

    // ========== 수정 ==========

    /**
     * 보호소 정보 수정 (보호소 회원이 직접 수정)
     * - Entity의 updateInfo 메서드 활용
     * @param id 보호소 ID
     * @param phone 전화번호
     * @param email 이메일
     * @param introduction 소개
     * @param adoptionProcedure 입양 절차
     * @param operatingHours 운영 시간
     * @return 수정된 Shelter
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    @Transactional
    public Shelter update(Long id, String phone, String email,
                         String introduction, String adoptionProcedure, String operatingHours) {
        Shelter shelter = shelterRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + id));

        shelter.updateInfo(phone, email, introduction, adoptionProcedure, operatingHours);
        return shelter;  // dirty checking
    }

    // ========== 삭제 ==========

    /**
     * 보호소 삭제
     * @param id 보호소 ID
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    @Transactional
    public void delete(Long id) {
        if (!shelterRepository.existsById(id)) {
            throw new EntityNotFoundException("Shelter not found: " + id);
        }
        shelterRepository.deleteById(id);
    }
}
