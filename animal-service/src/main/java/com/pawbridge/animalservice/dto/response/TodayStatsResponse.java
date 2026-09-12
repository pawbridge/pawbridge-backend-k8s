package com.pawbridge.animalservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 오늘 구조 및 호환용 APMS 수정일 통계 응답 DTO
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
     * 호환용 필드: 오늘 APMS 정보가 수정된 입양 상태 마릿수. 실제 입양 발생일이 아님.
     */
    private Long adoptedToday;
}
