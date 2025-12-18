package com.pawbridge.animalservice.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 일일 동물 등록 건수 통계 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DailyAnimalStatsResponse {

    /**
     * 날짜
     */
    private LocalDate date;

    /**
     * 동물 등록 건수
     */
    private Long count;
}
