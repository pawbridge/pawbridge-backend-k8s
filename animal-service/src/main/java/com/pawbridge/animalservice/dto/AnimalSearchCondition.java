package com.pawbridge.animalservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 동물 검색 조건 DTO (Elasticsearch Query 생성용)
 * - 복합 검색 조건을 담는 내부 처리 객체
 * - AnimalSearchRequest를 변환하여 생성
 * - Spring Data JPA Criteria API와 무관
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalSearchCondition {

    // 기본 검색
    /**
     * 키워드 검색 (품종, 특징, 발견 장소, 설명 등)
     */
    private String keyword;

    /**
     * 축종 (DOG, CAT, ETC)
     */
    private String species;

    /**
     * 품종
     */
    private String breed;

    /**
     * 상태 (NOTICE, PROTECT, ADOPTION_PENDING, ADOPTED 등)
     */
    private String status;

    /**
     * 성별 (MALE, FEMALE, UNKNOWN)
     */
    private String gender;

    /**
     * 중성화 여부 (YES, NO, UNKNOWN)
     */
    private String neuterStatus;

    // 지역 검색
    /**
     * 보호소 ID
     */
    private Long shelterId;

    /**
     * 보호소 주소 (지역 검색용)
     */
    private String shelterAddress;

    // 나이 범위
    /**
     * 최소 출생 연도
     */
    private Integer minBirthYear;

    /**
     * 최대 출생 연도
     */
    private Integer maxBirthYear;

    // 페이징 및 정렬
    /**
     * 페이지 번호 (0부터 시작)
     */
    @Builder.Default
    private Integer page = 0;

    /**
     * 페이지 크기
     */
    @Builder.Default
    private Integer size = 20;

    /**
     * 정렬 기준 (noticeEndDate, createdAt, favoriteCount)
     */
    @Builder.Default
    private String sortBy = "noticeEndDate";

    /**
     * 정렬 방향 (asc, desc)
     */
    @Builder.Default
    private String sortDirection = "asc";
}
