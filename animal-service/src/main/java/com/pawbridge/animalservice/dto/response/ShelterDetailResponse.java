package com.pawbridge.animalservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 보호소 상세 응답 DTO (상세 조회용)
 * - GET /api/shelters/{id} (상세 조회)
 * - 모든 필드를 포함하여 완전한 정보 제공
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShelterDetailResponse {

    // 기본 정보
    /**
     * 보호소 ID
     */
    private Long id;

    /**
     * 보호소 등록번호
     */
    private String careRegNo;

    /**
     * 보호소 이름
     */
    private String name;

    /**
     * 보호소 전화번호
     */
    private String phone;

    /**
     * 보호소 주소
     */
    private String address;

    /**
     * 보호소 대표자
     */
    private String ownerName;

    /**
     * 관할 기관
     */
    private String organizationName;

    // 자체 관리 정보
    /**
     * 보호소 이메일
     */
    private String email;

    /**
     * 보호소 소개
     */
    private String introduction;

    /**
     * 입양 절차 안내
     */
    private String adoptionProcedure;

    /**
     * 운영 시간
     */
    private String operatingHours;

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
