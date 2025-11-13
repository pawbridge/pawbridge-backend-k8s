package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.dto.request.CreateShelterRequest;
import com.pawbridge.animalservice.dto.request.UpdateShelterRequest;
import com.pawbridge.animalservice.dto.response.ShelterDetailResponse;
import com.pawbridge.animalservice.dto.response.ShelterResponse;
import com.pawbridge.animalservice.facade.ShelterFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 보호소 정보 REST API 컨트롤러
 * - 보호소 CRUD, 검색
 * - Controller는 요청 받고 Facade에 전달, 응답 반환만 담당
 */
@RestController
@RequestMapping("/api/shelters")
@RequiredArgsConstructor
public class ShelterController {

    private final ShelterFacade shelterFacade;

    /**
     * 보호소 등록 (관리자)
     * - POST /api/shelters
     */
    @PostMapping
    public ResponseEntity<ShelterDetailResponse> createShelter(
            @Valid @RequestBody CreateShelterRequest request
    ) {
        ShelterDetailResponse response = shelterFacade.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 보호소 목록 조회 (필터링 + 페이징)
     * - GET /api/shelters
     */
    @GetMapping
    public ResponseEntity<Page<ShelterResponse>> listShelters(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String organizationName,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<ShelterResponse> response;

        // 필터 조건에 따라 적절한 Facade 메서드 호출
        if (keyword != null) {
            // 통합 검색 (이름 또는 주소)
            response = shelterFacade.searchByNameOrAddress(keyword, pageable);
        } else if (name != null) {
            response = shelterFacade.searchByName(name, pageable);
        } else if (address != null) {
            response = shelterFacade.searchByAddress(address, pageable);
        } else if (organizationName != null) {
            response = shelterFacade.searchByOrganizationName(organizationName, pageable);
        } else {
            // 전체 조회
            response = shelterFacade.findAll(pageable);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 보호소 상세 조회
     * - GET /api/shelters/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ShelterDetailResponse> getShelter(@PathVariable Long id) {
        ShelterDetailResponse response = shelterFacade.findById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 보호소 등록번호로 조회
     * - GET /api/shelters/by-care-reg-no/{careRegNo}
     */
    @GetMapping("/by-care-reg-no/{careRegNo}")
    public ResponseEntity<ShelterDetailResponse> getShelterByCareRegNo(
            @PathVariable String careRegNo
    ) {
        ShelterDetailResponse response = shelterFacade.findByCareRegNo(careRegNo);
        return ResponseEntity.ok(response);
    }

    /**
     * 보호소 정보 수정 (보호소 회원)
     * - PUT /api/shelters/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ShelterDetailResponse> updateShelter(
            @PathVariable Long id,
            @Valid @RequestBody UpdateShelterRequest request
    ) {
        ShelterDetailResponse response = shelterFacade.update(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 보호소 삭제 (관리자)
     * - DELETE /api/shelters/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteShelter(@PathVariable Long id) {
        shelterFacade.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 전체 보호소 수 조회
     * - GET /api/shelters/count
     */
    @GetMapping("/count")
    public ResponseEntity<Long> countShelters() {
        long count = shelterFacade.countAll();
        return ResponseEntity.ok(count);
    }
}
