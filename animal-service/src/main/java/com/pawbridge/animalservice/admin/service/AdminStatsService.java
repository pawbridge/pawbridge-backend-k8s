package com.pawbridge.animalservice.admin.service;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 통계 서비스 인터페이스
 */
public interface AdminStatsService {

    /**
     * 일일 동물 등록 건수 통계
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 일별 동물 등록 건수 목록
     */
    List<DailyAnimalStatsResponse> getDailyAnimalStats(LocalDate startDate, LocalDate endDate);
}
