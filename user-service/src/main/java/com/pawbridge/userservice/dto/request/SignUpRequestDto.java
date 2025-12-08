package com.pawbridge.userservice.dto.request;

import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record SignUpRequestDto(
        @Email(message = "유효한 이메일을 입력하세요.")
        @NotBlank(message = "이메일은 필수입니다.")
        String email,
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 20)
        String name,
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다.")
        String password,
        @Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다.")
        String rePassword,
        Role role
) {

    public User toEntity(String email, String name, String password, String nickname) {
        // role이 null이면 기본값으로 ROLE_USER 사용
        Role userRole = (role != null) ? role : Role.ROLE_USER;
        return User.createLocalUser(email, name, password, nickname, userRole);
    }

}
