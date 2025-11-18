package com.pawbridge.userservice.dto.respone;

import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;

import java.time.LocalDateTime;

public record UserInfoResponseDto(
        Long userId,
        String email,
        String name,
        Role role
) {

    public static UserInfoResponseDto fromEntity(User user) {
        return new UserInfoResponseDto(
                user.getUserId(),
                user.getEmail(),
                user.getName(),
                user.getRole()
        );
    }

}
