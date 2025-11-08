package com.pawbridge.userservice.dto.respone;

public record RefreshTokenResponseDto(
        String accessToken,
        String refreshToken
) {
}
