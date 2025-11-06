package com.pawbridge.userservice.dto.respone;

import com.pawbridge.userservice.entity.User;

public record LoginResponseDto(
        Long userId,
        String email,
        String name,
        String token
) {
    public static LoginResponseDto fromEntity(User user, String token) {
        return new LoginResponseDto(
                user.getUserId(),
                user.getEmail(),
                user.getName(),
                token
        );
    }
}
