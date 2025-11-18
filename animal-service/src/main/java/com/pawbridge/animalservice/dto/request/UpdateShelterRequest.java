package com.pawbridge.animalservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 보호소 정보 수정 요청 DTO
 * - PUT /api/shelters/{id} 또는 PATCH /api/shelters/{id}
 * - 보호소 회원이 자신의 보호소 정보를 수정할 때 사용
 * - APMS 기본 정보(careRegNo, name, address, ownerName, organizationName)는 수정 불가
 * - 보호소가 직접 관리하는 정보만 수정 가능
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateShelterRequest {

    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    @Size(max = 50, message = "전화번호는 50자 이하여야 합니다")
    private String phone;

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
