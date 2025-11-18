package com.pawbridge.animalservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 동물 상세 응답 DTO (상세 조회용)
 * - GET /api/animals/{id} (상세 조회)
 * - 모든 필드를 포함하여 완전한 정보 제공
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnimalDetailResponse {

    // 기본 정보
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
     * 중성화 여부
     */
    private NeuterStatus neuterStatus;

    /**
     * 출생 연도
     */
    private Integer birthYear;

    /**
     * 나이 (계산된 값)
     */
    private Integer age;

    // 신체 특징
    /**
     * 체중
     */
    private String weight;

    /**
     * 색상
     */
    private String color;

    /**
     * 특징
     */
    private String specialMark;

    // 상태 및 공고 정보
    /**
     * 동물 상태
     */
    private AnimalStatus status;

    /**
     * 공고 시작일
     */
    private LocalDate noticeStartDate;

    /**
     * 공고 종료일
     */
    private LocalDate noticeEndDate;

    // 발견/접수 정보
    /**
     * 발견 장소
     */
    private String happenPlace;

    /**
     * 접수일 (APMS 데이터)
     */
    private LocalDate happenDate;

    // 이미지

    /**
     * 대표 이미지 URL
     */
    private String imageUrl;

    /**
     * 추가 이미지 URL
     */
    private String imageUrl2;

    // 설명
    /**
     * 보호소가 추가 작성한 설명
     */
    private String description;

    // 찜 정보
    /**
     * 찜 횟수
     */
    private Integer favoriteCount;

    // 보호소 정보

    /**
     * 보호소 ID
     */
    private Long shelterId;

    /**
     * 보호소 이름
     */
    private String shelterName;

    // APMS 연동 정보

    /**
     * APMS 유기번호 (APMS 배치 전용)
     */
    private String apmsDesertionNo;

    /**
     * APMS 처리상태 (APMS 배치 전용)
     */
    private String apmsProcessState;

    /**
     * APMS 마지막 동기화 시간
     */
    private LocalDateTime apmsUpdatedAt;

    /**
     * 데이터 출처
     */
    private ApiSource apiSource;

    // 메타 정보

    /**
     * 등록일
     */
    private LocalDateTime createdAt;

    /**
     * 수정일
     */
    private LocalDateTime updatedAt;
}
