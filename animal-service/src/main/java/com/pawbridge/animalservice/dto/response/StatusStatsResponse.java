package com.pawbridge.animalservice.dto.response;

import com.pawbridge.animalservice.enums.AnimalStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상태별 구조동물 현황 응답 DTO
 * - GET /api/v1/animals/stats/status
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StatusStatsResponse {

    /**
     * 상태 코드 (PROTECT, ADOPTED, RETURNED 등)
     */
    private AnimalStatus status;

    /**
     * 해당 상태 동물 수
     */
    private Long count;

    /**
     * 상태 한글 라벨 (JPQL에서 enum 필드 직접 접근 불가하여 계산 방식 사용)
     * - 예: PROTECT → "보호중", ADOPTED → "종료(입양)"
     */
    public String getLabel() {
        return status != null ? status.getDescription() : null;
    }
}

