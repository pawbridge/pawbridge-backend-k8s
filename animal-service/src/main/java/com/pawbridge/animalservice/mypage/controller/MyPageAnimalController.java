package com.pawbridge.animalservice.mypage.controller;

import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.mypage.service.MyPageAnimalService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 마이페이지용 동물 API 컨트롤러
 * - FeignClient 전용
 * - MySQL 기반 단순 조회
 */
@RestController
@RequestMapping("/api/v1/mypage/animals")
@RequiredArgsConstructor
public class MyPageAnimalController {

    private final MyPageAnimalService myPageAnimalService;

    /**
     * 여러 동물 ID로 일괄 조회 (FeignClient용)
     * - POST /api/v1/mypage/animals/batch
     * - MySQL IN 쿼리 사용
     * - user-service의 찜 목록 조회에 사용
     */
    @PostMapping("/batch")
    public ResponseEntity<List<AnimalResponse>> getAnimalsByIds(
            @RequestBody List<Long> animalIds) {
        List<AnimalResponse> animals = myPageAnimalService.findByIds(animalIds);
        return ResponseEntity.ok(animals);
    }

    /**
     * 보호소별 동물 목록 조회 (FeignClient용)
     * - GET /api/v1/mypage/animals/by-shelter/{shelterId}
     * - MySQL 사용
     * - 보호소 직원이 등록한 동물 목록 조회에 사용
     */
    @GetMapping("/by-shelter/{shelterId}")
    public ResponseEntity<Page<AnimalResponse>> getAnimalsByShelterId(
            @PathVariable Long shelterId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AnimalResponse> animals = myPageAnimalService.findByShelterId(shelterId, pageable);
        return ResponseEntity.ok(animals);
    }
}
