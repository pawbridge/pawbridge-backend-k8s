package com.pawbridge.animalservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 오늘의 구조/입양 통계 응답 DTO
 * - GET /api/v1/animals/stats/today
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TodayStatsResponse {

    /**
     * 조회 기준 날짜 (KST)
     */
    private LocalDate date;

    /**
     * 오늘 구조된 마릿수 (happenDate = 오늘)
     */
    private Long rescuedToday;

    /**
     * 오늘 입양된 마릿수 (status=ADOPTED AND DATE(apmsUpdatedAt)=오늘)
     */
    private Long adoptedToday;
}
