package com.pawbridge.animalservice.controller;

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
     * 동물 목록 조회 (필터링 + 페이징)
     * - GET /api/animals
     */
    @GetMapping
    public ResponseEntity<Page<AnimalResponse>> listAnimals(
            @RequestParam(required = false) Species species,
            @RequestParam(required = false) Gender gender,
            @RequestParam(required = false) AnimalStatus status,
            @RequestParam(required = false) String breed,
            @RequestParam(required = false) Long shelterId,
            @PageableDefault(size = 20, sort = "noticeEndDate", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<AnimalResponse> response;

        // 필터 조건에 따라 적절한 Facade 메서드 호출
        if (shelterId != null && status != null) {
            response = animalFacade.findByShelterIdAndStatus(shelterId, status, pageable);
        } else if (shelterId != null && species != null) {
            response = animalFacade.findByShelterIdAndSpecies(shelterId, species, pageable);
        } else if (shelterId != null) {
            response = animalFacade.findByShelterId(shelterId, pageable);
        } else if (species != null && status != null) {
            response = animalFacade.findBySpeciesAndStatus(species, status, pageable);
        } else if (species != null && gender != null) {
            response = animalFacade.findBySpeciesAndGender(species, gender, pageable);
        } else if (species != null && breed != null) {
            response = animalFacade.searchBySpeciesAndBreed(species, breed, pageable);
        } else if (species != null) {
            response = animalFacade.findBySpecies(species, pageable);
        } else if (status != null) {
            response = animalFacade.findByStatus(status, pageable);
        } else if (breed != null) {
            response = animalFacade.searchByBreed(breed, pageable);
        } else {
            // 기본: 공고 종료 임박순 (메인 페이지)
            response = animalFacade.findNoticeAnimalsOrderByNoticeEndDate(pageable);
        }

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
     * 특징 키워드 검색
     * - GET /api/animals/search/special-mark?keyword=순함
     */
    @GetMapping("/search/special-mark")
    public ResponseEntity<Page<AnimalResponse>> searchBySpecialMark(
            @RequestParam String keyword,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AnimalResponse> response = animalFacade.searchBySpecialMark(keyword, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 발견 장소 검색
     * - GET /api/animals/search/happen-place?place=서울
     */
    @GetMapping("/search/happen-place")
    public ResponseEntity<Page<AnimalResponse>> searchByHappenPlace(
            @RequestParam String place,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AnimalResponse> response = animalFacade.searchByHappenPlace(place, pageable);
        return ResponseEntity.ok(response);
    }
}
