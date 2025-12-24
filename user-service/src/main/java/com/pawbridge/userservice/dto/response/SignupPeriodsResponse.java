package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 기간별 가입자 수 통계 응답 DTO
 * - 오늘, 최근 7일, 최근 30일, 이번 달의 일별 가입자 수를 한번에 반환
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignupPeriodsResponse {

    /**
     * 오늘 가입자 수 (일별)
     */
    private List<DailySignupStatsResponse> today;

    /**
     * 최근 7일 가입자 수 (일별)
     */
    private List<DailySignupStatsResponse> last7Days;

    /**
     * 최근 30일 가입자 수 (일별)
     */
    private List<DailySignupStatsResponse> last30Days;

    /**
     * 이번 달 가입자 수 (일별)
     */
    private List<DailySignupStatsResponse> thisMonth;
}
