package com.pawbridge.userservice.dto.response;

import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponseDto {

    private Long userId;
    private String email;
    private String name;
    private String nickname;
    private String provider;
    private Role role;
    private LocalDateTime createdAt;

    public static UserInfoResponseDto fromEntity(User user) {
        return UserInfoResponseDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .nickname(user.getNickname())
                .provider(user.getProvider())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
