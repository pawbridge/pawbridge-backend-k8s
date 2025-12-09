package com.pawbridge.userservice.dto.response;

import com.pawbridge.userservice.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignUpResponseDto {

    private Long userId;
    private String email;
    private String name;
    private String nickname;

    public static SignUpResponseDto fromEntity(User user) {
        return SignUpResponseDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .name(user.getName())
                .nickname(user.getNickname())
                .build();
    }
}
