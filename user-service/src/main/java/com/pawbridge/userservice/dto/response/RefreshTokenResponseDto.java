package com.pawbridge.userservice.dto.response;

public record RefreshTokenResponseDto(
        String accessToken,
        String refreshToken
) {
}
