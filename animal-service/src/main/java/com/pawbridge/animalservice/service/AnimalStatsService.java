package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.RegionalRescueStatsResponse;
import com.pawbridge.animalservice.dto.response.StatusStatsResponse;
import com.pawbridge.animalservice.dto.response.TodayStatsResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 공개 통계 서비스 인터페이스
 * - 인증 없이 접근 가능한 통계 API용
 */
public interface AnimalStatsService {

    /**
     * 오늘의 구조/입양 통계
     *
     * @return 오늘 구조 마릿수 + 입양 마릿수
     */
    TodayStatsResponse getTodayStats();

    /**
     * 기간별 상태별 현황
     *
     * @param startDate 시작일 (null이면 최근 30일)
     * @param endDate   종료일 (null이면 오늘)
     * @return 상태별 건수 목록 (count 내림차순)
     */
    List<StatusStatsResponse> getStatusStats(LocalDate startDate, LocalDate endDate);

    /**
     * 기간별 지역별 구조 통계
     *
     * @param startDate 시작일 (null이면 최근 30일)
     * @param endDate   종료일 (null이면 오늘)
     * @return 시/도별 구조 건수 목록 (count 내림차순)
     */
    List<RegionalRescueStatsResponse> getRegionalStats(LocalDate startDate, LocalDate endDate);
}
