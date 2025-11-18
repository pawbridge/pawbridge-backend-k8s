package com.pawbridge.animalservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 동물 응답 DTO (목록 조회용)
 * - GET /api/animals (목록 조회)
 * - 핵심 정보만 포함하여 가볍게 구성
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnimalResponse {

    /**
     * 동물 ID
     */
    private Long id;

    /**
     * 공고번호
     */
    private String apmsNoticeNo;

    /**
     * 축종
     */
    private Species species;

    /**
     * 품종명
     */
    private String breed;

    /**
     * 성별
     */
    private Gender gender;

    /**
     * 출생 연도
     */
    private Integer birthYear;

    /**
     * 나이 (계산된 값)
     */
    private Integer age;

    /**
     * 동물 상태
     */
    private AnimalStatus status;

    /**
     * 공고 종료일
     */
    private LocalDate noticeEndDate;

    /**
     * 대표 이미지 URL
     */
    private String imageUrl;

    /**
     * 찜 횟수
     */
    private Integer favoriteCount;

    /**
     * 보호소 ID
     */
    private Long shelterId;

    /**
     * 보호소 이름 (간단 표시용)
     */
    private String shelterName;

    /**
     * 등록일
     */
    private LocalDateTime createdAt;
}
