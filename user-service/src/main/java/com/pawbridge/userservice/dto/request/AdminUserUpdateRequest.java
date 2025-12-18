package com.pawbridge.userservice.dto.request;

import com.pawbridge.userservice.entity.Role;
import jakarta.validation.constraints.Pattern;

/**
 * 관리자용 회원 수정 요청 DTO
 */
public record AdminUserUpdateRequest(
        /**
         * 닉네임
         */
        @Pattern(regexp = "^[a-zA-Z0-9가-힣]{2,10}$", message = "닉네임은 2~10자의 영문, 숫자, 한글만 가능합니다.")
        String nickname,

        /**
         * 역할
         */
        Role role,

        /**
         * 보호소 등록번호 (ROLE_SHELTER인 경우)
         */
        String careRegNo
) {
}
