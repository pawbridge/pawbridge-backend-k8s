package com.pawbridge.backend.user.internal.adminupdate.web;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import jakarta.validation.constraints.Pattern;

public record AdminUserUpdateRequest(
        @Pattern(regexp = "^[a-zA-Z0-9가-힣]{2,10}$", message = "닉네임은 2~10자의 영문, 숫자, 한글만 가능합니다.")
        String nickname, AdminUserUpdate.Role role, String careRegNo) { }
