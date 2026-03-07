package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.dto.response.RegionalRescueStatsResponse;
import com.pawbridge.animalservice.dto.response.StatusStatsResponse;
import com.pawbridge.animalservice.dto.response.TodayStatsResponse;
import com.pawbridge.animalservice.service.AnimalStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 유기동물 공개 통계 API 컨트롤러
 * - 인증(JWT) 없이 접근 가능한 공개 엔드포인트
 * - API Gateway: animal-service-public 라우트로 처리 (수정 불필요)
 */
@RestController
@RequestMapping("/api/v1/animals/stats")
@RequiredArgsConstructor
public class AnimalStatsController {

    private final AnimalStatsService animalStatsService;

    /**
     * 오늘의 구조/입양 통계
     * - GET /api/v1/animals/stats/today
     * - 메인 페이지 위젯용
     *
     * 응답 예시:
     * {
     *   "date": "2026-03-07",
     *   "rescuedToday": 23,
     *   "adoptedToday": 5
     * }
     */
    @GetMapping("/today")
    public ResponseEntity<TodayStatsResponse> getTodayStats() {
        TodayStatsResponse response = animalStatsService.getTodayStats();
        return ResponseEntity.ok(response);
    }

    /**
     * 기간별 상태별 현황
     * - GET /api/v1/animals/stats/status
     * - startDate/endDate 생략 시 최근 30일
     * - 통계 페이지 도넛/바 차트용
     *
     * 응답 예시:
     * [
     *   { "status": "PROTECT",  "label": "보호중", "count": 7182 },
     *   { "status": "ADOPTED",  "label": "종료(입양)", "count": 3203 }
     * ]
     */
    @GetMapping("/status")
    public ResponseEntity<List<StatusStatsResponse>> getStatusStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        List<StatusStatsResponse> response = animalStatsService.getStatusStats(startDate, endDate);
        return ResponseEntity.ok(response);
    }

    /**
     * 기간별 지역별 구조 통계
     * - GET /api/v1/animals/stats/regional
     * - startDate/endDate 생략 시 최근 30일
     * - 통계 페이지 지도 시각화용 (포인핸드 차별점)
     *
     * 응답 예시:
     * [
     *   { "region": "경상남도", "count": 1523 },
     *   { "region": "경기도",   "count": 986  }
     * ]
     */
    @GetMapping("/regional")
    public ResponseEntity<List<RegionalRescueStatsResponse>> getRegionalStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        List<RegionalRescueStatsResponse> response = animalStatsService.getRegionalStats(startDate, endDate);
        return ResponseEntity.ok(response);
    }
}
