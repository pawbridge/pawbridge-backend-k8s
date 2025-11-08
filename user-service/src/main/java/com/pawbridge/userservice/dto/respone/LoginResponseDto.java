package com.pawbridge.userservice.dto.respone;

import com.pawbridge.userservice.entity.User;

public record LoginResponseDto(
        Long userId,
        String email,
        String name,
        String accessToken,
        String refreshToken
) {
    public static LoginResponseDto fromEntity(User user, String accessToken, String refreshToken) {
        return new LoginResponseDto(
                user.getUserId(),
                user.getEmail(),
                user.getName(),
                accessToken,
                refreshToken
        );
    }
}
