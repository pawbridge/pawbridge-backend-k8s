package com.pawbridge.animalservice.facade;

import com.pawbridge.animalservice.dto.request.CreateShelterRequest;
import com.pawbridge.animalservice.dto.request.UpdateShelterRequest;
import com.pawbridge.animalservice.dto.response.ShelterDetailResponse;
import com.pawbridge.animalservice.dto.response.ShelterResponse;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.service.ShelterCommandService;
import com.pawbridge.animalservice.service.ShelterQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Shelter Facade
 * - Command + Query 통합
 * - Controller의 단일 진입점
 * - CQRS 내부 구조를 외부로부터 숨김
 * - DTO 기반 처리
 */
@Service
@RequiredArgsConstructor
public class ShelterFacade {

    private final ShelterCommandService commandService;
    private final ShelterQueryService queryService;

    /**
     * 보호소 생성
     */
    @Transactional
    public ShelterDetailResponse create(CreateShelterRequest request) {
        return commandService.create(request);
    }

    /**
     * APMS 데이터로부터 보호소 생성 (배치 전용)
     * - Entity를 직접 받음 (ApmsShelterMapper에서 생성)
     */
    @Transactional
    public Shelter createFromApms(Shelter shelter) {
        return commandService.createFromApms(shelter);
    }

    /**
     * 보호소 정보 수정
     */
    @Transactional
    public ShelterDetailResponse update(Long id, UpdateShelterRequest request) {
        return commandService.update(id, request);
    }

    /**
     * 보호소 삭제
     */
    @Transactional
    public void delete(Long id) {
        commandService.delete(id);
    }

    /**
     * ID로 보호소 상세 조회
     */
    @Transactional(readOnly = true)
    public ShelterDetailResponse findById(Long id) {
        return queryService.findById(id);
    }

    /**
     * APMS 보호소 등록번호로 조회
     */
    @Transactional(readOnly = true)
    public ShelterDetailResponse findByCareRegNo(String careRegNo) {
        return queryService.findByCareRegNo(careRegNo);
    }

    /**
     * 전체 보호소 조회
     */
    @Transactional(readOnly = true)
    public Page<ShelterResponse> findAll(Pageable pageable) {
        return queryService.findAll(pageable);
    }

    /**
     * 보호소 이름으로 검색
     */
    @Transactional(readOnly = true)
    public Page<ShelterResponse> searchByName(String name, Pageable pageable) {
        return queryService.searchByName(name, pageable);
    }

    /**
     * 보호소 주소로 검색
     */
    @Transactional(readOnly = true)
    public Page<ShelterResponse> searchByAddress(String address, Pageable pageable) {
        return queryService.searchByAddress(address, pageable);
    }

    /**
     * 관할 기관으로 검색
     */
    @Transactional(readOnly = true)
    public Page<ShelterResponse> searchByOrganizationName(String organizationName, Pageable pageable) {
        return queryService.searchByOrganizationName(organizationName, pageable);
    }

    /**
     * 이름 또는 주소로 검색
     */
    @Transactional(readOnly = true)
    public Page<ShelterResponse> searchByNameOrAddress(String keyword, Pageable pageable) {
        return queryService.searchByNameOrAddress(keyword, pageable);
    }

    /**
     * 여러 개의 careRegNo로 Shelter 조회 (배치)
     * - Entity 반환 (배치 작업에서 사용)
     */
    @Transactional(readOnly = true)
    public List<Shelter> findByCareRegNoIn(List<String> careRegNos) {
        return queryService.findByCareRegNoIn(careRegNos);
    }

    /**
     * 전체 보호소 수 카운트
     */
    @Transactional(readOnly = true)
    public long countAll() {
        return queryService.countAll();
    }
}
