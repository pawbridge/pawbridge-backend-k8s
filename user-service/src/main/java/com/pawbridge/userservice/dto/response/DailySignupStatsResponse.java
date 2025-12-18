package com.pawbridge.userservice.dto.response;

import java.time.LocalDate;

/**
 * 일별 가입자 수 통계 응답 DTO
 */
public record DailySignupStatsResponse(
        /**
         * 날짜
         */
        LocalDate date,

        /**
         * 가입자 수
         */
        Long count
) {
}
