package com.pawbridge.animalservice.dto.request;

import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 동물 검색 요청 DTO
 * - Phase 1: MySQL Specification 기반 검색
 * - Phase 4: OpenSearch 전환 예정
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalSearchRequest {

    /**
     * 축종 (개, 고양이, 기타)
     */
    private Species species;

    /**
     * 품종 (부분 검색)
     */
    private String breed;

    /**
     * 성별
     */
    private Gender gender;

    /**
     * 중성화 여부
     */
    private NeuterStatus neuterStatus;

    /**
     * 동물 상태 (공고중, 보호중 등)
     */
    private AnimalStatus status;

    /**
     * 나이 범위 - 최소 (만 나이)
     * 예: minAge=1 → 1살 이상
     */
    private Integer minAge;

    /**
     * 나이 범위 - 최대 (만 나이)
     * 예: maxAge=5 → 5살 이하
     */
    private Integer maxAge;

    /**
     * 지역 검색 (시도)
     * 예: "서울", "경기", "부산"
     * Shelter.careAddr에서 LIKE 검색
     */
    private String region;

    /**
     * 지역 검색 (시군구)
     * 예: "강남구", "수원시"
     * Shelter.careAddr에서 LIKE 검색
     */
    private String city;

    /**
     * 키워드 검색 (품종 + 특징 + 발견장소 통합 검색)
     * - breed, specialMark, happenPlace에서 OR 검색
     */
    private String keyword;
}
