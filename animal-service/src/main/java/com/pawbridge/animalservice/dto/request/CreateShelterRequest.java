package com.pawbridge.animalservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 보호소 등록 요청 DTO
 * - POST /api/shelters
 * - 관리자가 보호소를 수동 등록할 때 사용
 * - APMS 배치는 createFromApms() 정적 팩토리 메서드 사용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateShelterRequest {

    // 필수 필드 (2개)
    /**
     * 보호소 등록번호
     * - 예: "348527200900001"
     * - UNIQUE 제약
     */
    @NotBlank(message = "보호소 등록번호는 필수입니다")
    @Size(max = 50, message = "보호소 등록번호는 50자 이하여야 합니다")
    private String careRegNo;

    /**
     * 보호소 이름
     * - 예: "창원동물보호센터"
     */
    @NotBlank(message = "보호소 이름은 필수입니다")
    @Size(max = 200, message = "보호소 이름은 200자 이하여야 합니다")
    private String name;

    // 선택 필드 (APMS 정보)
    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    @Size(max = 50, message = "전화번호는 50자 이하여야 합니다")
    private String phone;

    /**
     * 보호소 주소
     * - 예: "경상남도 창원시 성산구 공단로474번길 117 (상복동)"
     */
    @Size(max = 500, message = "주소는 500자 이하여야 합니다")
    private String address;

    /**
     * 보호소 대표자
     * - 예: "창원시장"
     */
    @Size(max = 100, message = "대표자명은 100자 이하여야 합니다")
    private String ownerName;

    /**
     * 관할 기관
     * - 예: "경상남도 창원시 의창성산구"
     */
    @Size(max = 200, message = "관할 기관명은 200자 이하여야 합니다")
    private String organizationName;

    // 선택 필드 (자체 관리)
    /**
     * 보호소 이메일
     */
    @Email(message = "올바른 이메일 형식이 아닙니다")
    @Size(max = 100, message = "이메일은 100자 이하여야 합니다")
    private String email;

    /**
     * 보호소 소개
     */
    @Size(max = 2000, message = "소개는 2000자 이하여야 합니다")
    private String introduction;

    /**
     * 입양 절차 안내
     */
    @Size(max = 2000, message = "입양 절차 안내는 2000자 이하여야 합니다")
    private String adoptionProcedure;

    /**
     * 운영 시간
     * - 예: "평일 09:00-18:00, 주말 10:00-17:00"
     */
    @Size(max = 200, message = "운영 시간은 200자 이하여야 합니다")
    private String operatingHours;
}
