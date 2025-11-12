package com.pawbridge.animalservice.facade;

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
 */
@Service
@RequiredArgsConstructor
public class ShelterFacade {

    private final ShelterCommandService commandService;
    private final ShelterQueryService queryService;

    // ========== Command 위임 (CUD) ==========

    /**
     * 보호소 생성
     */
    @Transactional
    public Shelter create(Shelter shelter) {
        return commandService.create(shelter);
    }

    /**
     * APMS 데이터로부터 보호소 생성 (배치 전용)
     */
    @Transactional
    public Shelter createFromApms(Shelter shelter) {
        return commandService.createFromApms(shelter);
    }

    /**
     * 보호소 정보 수정
     */
    @Transactional
    public Shelter update(Long id, String phone, String email,
                         String introduction, String adoptionProcedure, String operatingHours) {
        return commandService.update(id, phone, email, introduction, adoptionProcedure, operatingHours);
    }

    /**
     * 보호소 삭제
     */
    @Transactional
    public void delete(Long id) {
        commandService.delete(id);
    }

    // ========== Query 위임 (R) ==========

    /**
     * ID로 보호소 조회
     */
    @Transactional(readOnly = true)
    public Shelter findById(Long id) {
        return queryService.findById(id);
    }

    /**
     * APMS 보호소 등록번호로 조회
     */
    @Transactional(readOnly = true)
    public Shelter findByCareRegNo(String careRegNo) {
        return queryService.findByCareRegNo(careRegNo);
    }

    /**
     * 전체 보호소 조회
     */
    @Transactional(readOnly = true)
    public Page<Shelter> findAll(Pageable pageable) {
        return queryService.findAll(pageable);
    }

    /**
     * 보호소 이름으로 검색
     */
    @Transactional(readOnly = true)
    public Page<Shelter> searchByName(String name, Pageable pageable) {
        return queryService.searchByName(name, pageable);
    }

    /**
     * 보호소 주소로 검색
     */
    @Transactional(readOnly = true)
    public Page<Shelter> searchByAddress(String address, Pageable pageable) {
        return queryService.searchByAddress(address, pageable);
    }

    /**
     * 관할 기관으로 검색
     */
    @Transactional(readOnly = true)
    public Page<Shelter> searchByOrganizationName(String organizationName, Pageable pageable) {
        return queryService.searchByOrganizationName(organizationName, pageable);
    }

    /**
     * 이름 또는 주소로 검색
     */
    @Transactional(readOnly = true)
    public Page<Shelter> searchByNameOrAddress(String keyword, Pageable pageable) {
        return queryService.searchByNameOrAddress(keyword, pageable);
    }

    /**
     * 여러 개의 careRegNo로 Shelter 조회 (배치)
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
