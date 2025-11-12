package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.repository.ShelterRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Shelter Query Service (R)
 * - 보호소 조회, 검색 담당
 * - 읽기 전용 트랜잭션
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShelterQueryService {

    private final ShelterRepository shelterRepository;

    // ========== 단건 조회 ==========

    /**
     * ID로 보호소 조회
     * @param id 보호소 ID
     * @return Shelter
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    public Shelter findById(Long id) {
        return shelterRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + id));
    }

    /**
     * APMS 보호소 등록번호로 조회
     * @param careRegNo APMS 보호소 등록번호
     * @return Shelter
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    public Shelter findByCareRegNo(String careRegNo) {
        return shelterRepository.findByCareRegNo(careRegNo)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + careRegNo));
    }

    // ========== 목록 조회 ==========

    /**
     * 전체 보호소 조회
     */
    public Page<Shelter> findAll(Pageable pageable) {
        return shelterRepository.findAll(pageable);
    }

    /**
     * 보호소 이름으로 검색
     */
    public Page<Shelter> searchByName(String name, Pageable pageable) {
        return shelterRepository.findByNameContaining(name, pageable);
    }

    /**
     * 보호소 주소로 검색
     */
    public Page<Shelter> searchByAddress(String address, Pageable pageable) {
        return shelterRepository.findByAddressContaining(address, pageable);
    }

    /**
     * 관할 기관으로 검색
     */
    public Page<Shelter> searchByOrganizationName(String organizationName, Pageable pageable) {
        return shelterRepository.findByOrganizationNameContaining(organizationName, pageable);
    }

    /**
     * 이름 또는 주소로 검색
     */
    public Page<Shelter> searchByNameOrAddress(String keyword, Pageable pageable) {
        return shelterRepository.findByNameOrAddress(keyword, keyword, pageable);
    }

    // ========== 배치 작업용 ==========

    /**
     * 여러 개의 careRegNo로 Shelter 조회
     * - 배치 작업에서 대량 조회
     */
    public List<Shelter> findByCareRegNoIn(List<String> careRegNos) {
        return shelterRepository.findByCareRegNoIn(careRegNos);
    }

    // ========== 통계 ==========

    /**
     * 전체 보호소 수 카운트
     */
    public long countAll() {
        return shelterRepository.countAllShelters();
    }
}
