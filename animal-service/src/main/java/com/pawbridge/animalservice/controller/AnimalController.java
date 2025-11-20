package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.animalservice.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.facade.AnimalFacade;
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
 * 동물 정보 REST API 컨트롤러
 * - 동물 CRUD, 검색, 필터링
 * - Controller는 요청 받고 Facade에 전달, 응답 반환만 담당
 */
@RestController
@RequestMapping("/api/animals")
@RequiredArgsConstructor
public class AnimalController {

    private final AnimalFacade animalFacade;

    /**
     * 동물 등록 (보호소 회원/관리자)
     * - POST /api/animals
     */
    @PostMapping
    public ResponseEntity<AnimalDetailResponse> createAnimal(
            @Valid @RequestBody CreateAnimalRequest request
    ) {
        AnimalDetailResponse response = animalFacade.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 동물 목록 조회 및 검색 (통합 엔드포인트)
     * - GET /api/animals
     * - Phase 1: MySQL Specification 기반
     * - Phase 4: OpenSearch로 전환 예정
     *
     * 쿼리 파라미터:
     * - species: 축종 (DOG, CAT, ETC)
     * - breed: 품종 (부분 검색)
     * - gender: 성별 (MALE, FEMALE, UNKNOWN)
     * - neuterStatus: 중성화 여부 (YES, NO, UNKNOWN)
     * - status: 상태 (PROTECT, ADOPTED 등)
     * - minAge: 최소 나이
     * - maxAge: 최대 나이
     * - region: 지역(시도)
     * - city: 지역(시군구)
     * - keyword: 통합 검색 (품종+특징+발견장소)
     *
     * 정렬:
     * - createdAt,desc: 최신 등록순 (기본값)
     * - noticeEndDate,asc: 공고 종료 임박순
     * - createdAt,asc: 오래된 순
     *
     * 예시:
     * - /api/animals?status=PROTECT&species=DOG (보호중인 강아지)
     * - /api/animals (전체 조회)
     * - /api/animals?species=DOG&gender=MALE&minAge=1&maxAge=5&region=서울
     */
    @GetMapping
    public ResponseEntity<Page<AnimalResponse>> listAnimals(
            @ModelAttribute AnimalSearchRequest request,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<AnimalResponse> response = animalFacade.searchAnimals(request, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 동물 상세 조회
     * - GET /api/animals/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<AnimalDetailResponse> getAnimal(@PathVariable Long id) {
        AnimalDetailResponse response = animalFacade.findById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * APMS 유기번호로 동물 조회
     * - GET /api/animals/apms/{apmsDesertionNo}
     */
    @GetMapping("/apms/{apmsDesertionNo}")
    public ResponseEntity<AnimalDetailResponse> getAnimalByApmsNo(@PathVariable String apmsDesertionNo) {
        AnimalDetailResponse response = animalFacade.findByApmsDesertionNo(apmsDesertionNo);
        return ResponseEntity.ok(response);
    }

    /**
     * 동물 상태 변경
     * - PATCH /api/animals/{id}/status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<AnimalDetailResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnimalStatusRequest request
    ) {
        AnimalDetailResponse response = animalFacade.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 동물 설명 수정
     * - PATCH /api/animals/{id}/description
     */
    @PatchMapping("/{id}/description")
    public ResponseEntity<AnimalDetailResponse> updateDescription(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnimalDescriptionRequest request
    ) {
        AnimalDetailResponse response = animalFacade.updateDescription(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 동물 삭제
     * - DELETE /api/animals/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAnimal(@PathVariable Long id) {
        animalFacade.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 공고 종료 임박 동물 조회 (D-3 이내)
     * - GET /api/animals/expiring-soon
     */
    @GetMapping("/expiring-soon")
    public ResponseEntity<Page<AnimalResponse>> listExpiringSoon(
            @PageableDefault(size = 20, sort = "noticeEndDate", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<AnimalResponse> response = animalFacade.findExpiringSoonAnimals(pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 찜 횟수 증가
     * - POST /api/animals/{id}/favorite/increment
     * - user-service에서 Kafka 이벤트로 호출 예정
     * - 현재는 테스트용 엔드포인트
     */
    @PostMapping("/{id}/favorite/increment")
    public ResponseEntity<Void> incrementFavoriteCount(@PathVariable Long id) {
        animalFacade.incrementFavoriteCount(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 찜 횟수 감소
     * - POST /api/animals/{id}/favorite/decrement
     * - user-service에서 Kafka 이벤트로 호출 예정
     * - 현재는 테스트용 엔드포인트
     */
    @PostMapping("/{id}/favorite/decrement")
    public ResponseEntity<Void> decrementFavoriteCount(@PathVariable Long id) {
        animalFacade.decrementFavoriteCount(id);
        return ResponseEntity.ok().build();
    }

}
