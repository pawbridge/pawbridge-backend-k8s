package com.pawbridge.animalservice.admin.controller;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;
import com.pawbridge.animalservice.admin.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 통계 컨트롤러
 * - 동물 관련 통계
 * - ROLE_ADMIN만 접근 가능 (API Gateway에서 체크)
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /**
     * 일일 동물 등록 건수 통계
     * - GET /api/v1/admin/stats/daily-animals?startDate=2024-01-01&endDate=2024-01-31
     */
    @GetMapping("/daily-animals")
    public ResponseEntity<List<DailyAnimalStatsResponse>> getDailyAnimalStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<DailyAnimalStatsResponse> stats = adminStatsService.getDailyAnimalStats(startDate, endDate);

        return ResponseEntity.ok(stats);
    }
}
