package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.ShelterDetailResponse;
import com.pawbridge.animalservice.dto.response.ShelterResponse;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.mapper.ShelterMapper;
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
 * - Response DTO 반환
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShelterQueryService {

    private final ShelterRepository shelterRepository;
    private final ShelterMapper mapper;

    /**
     * ID로 보호소 상세 조회
     * @param id 보호소 ID
     * @return ShelterDetailResponse
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    public ShelterDetailResponse findById(Long id) {
        Shelter shelter = shelterRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + id));
        return mapper.toDetailResponse(shelter);
    }

    /**
     * APMS 보호소 등록번호로 조회
     * @param careRegNo APMS 보호소 등록번호
     * @return ShelterDetailResponse
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    public ShelterDetailResponse findByCareRegNo(String careRegNo) {
        Shelter shelter = shelterRepository.findByCareRegNo(careRegNo)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + careRegNo));
        return mapper.toDetailResponse(shelter);
    }

    /**
     * 전체 보호소 조회
     */
    public Page<ShelterResponse> findAll(Pageable pageable) {
        Page<Shelter> shelters = shelterRepository.findAll(pageable);
        return shelters.map(mapper::toResponse);
    }

    /**
     * 보호소 이름으로 검색
     */
    public Page<ShelterResponse> searchByName(String name, Pageable pageable) {
        Page<Shelter> shelters = shelterRepository.findByNameContaining(name, pageable);
        return shelters.map(mapper::toResponse);
    }

    /**
     * 보호소 주소로 검색
     */
    public Page<ShelterResponse> searchByAddress(String address, Pageable pageable) {
        Page<Shelter> shelters = shelterRepository.findByAddressContaining(address, pageable);
        return shelters.map(mapper::toResponse);
    }

    /**
     * 관할 기관으로 검색
     */
    public Page<ShelterResponse> searchByOrganizationName(String organizationName, Pageable pageable) {
        Page<Shelter> shelters = shelterRepository.findByOrganizationNameContaining(organizationName, pageable);
        return shelters.map(mapper::toResponse);
    }

    /**
     * 이름 또는 주소로 검색
     */
    public Page<ShelterResponse> searchByNameOrAddress(String keyword, Pageable pageable) {
        Page<Shelter> shelters = shelterRepository.findByNameOrAddress(keyword, keyword, pageable);
        return shelters.map(mapper::toResponse);
    }

    /**
     * 여러 개의 careRegNo로 Shelter 조회
     * - 배치 작업에서 대량 조회
     */
    public List<Shelter> findByCareRegNoIn(List<String> careRegNos) {
        return shelterRepository.findByCareRegNoIn(careRegNos);
    }

    /**
     * 전체 보호소 수 카운트
     */
    public long countAll() {
        return shelterRepository.countAllShelters();
    }
}
