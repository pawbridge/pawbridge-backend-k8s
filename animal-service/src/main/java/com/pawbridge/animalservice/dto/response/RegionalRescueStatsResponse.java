package com.pawbridge.animalservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지역별 구조동물 통계 응답 DTO
 * - GET /api/v1/animals/stats/regional
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RegionalRescueStatsResponse {

    /**
     * 시/도 풀네임 (경상남도, 서울특별시 등)
     * - Shelter.address 첫 단어에서 추출
     */
    private String region;

    /**
     * 구조 마릿수
     */
    private Long count;
}
