package com.pawbridge.animalservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 보호소 응답 DTO (목록 조회용)
 * - GET /api/shelters (목록 조회)
 * - 핵심 정보만 포함하여 가볍게 구성
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShelterResponse {

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
     * 관할 기관
     */
    private String organizationName;

    /**
     * 등록일
     */
    private LocalDateTime createdAt;
}
