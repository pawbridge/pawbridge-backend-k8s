package com.pawbridge.userservice.dto.request;

import jakarta.validation.constraints.NotNull;

public record LogoutRequestDto(
        @NotNull(message = "사용자 ID는 필수입니다.")
        Long userId
) {
}
