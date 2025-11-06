package com.pawbridge.userservice.dto.respone;

import com.pawbridge.userservice.entity.User;

public record SignUpResponseDto (
        Long userId,
        String email,
        String name,
        String phoneNumber
) {

    public static SignUpResponseDto fromEntity(User user) {
        return new SignUpResponseDto(
                user.getUserId(),
                user.getEmail(),
                user.getName(),
                user.getPhoneNumber()
        );
    }

}
