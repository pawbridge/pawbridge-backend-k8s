package com.pawbridge.userservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNicknameRequestDto {

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 2, max = 30, message = "닉네임은 2~30자여야 합니다.")
    @Pattern(regexp = "^(?=.*[가-힣a-zA-Z])[가-힣a-zA-Z0-9]+$",
            message = "닉네임은 한글, 영문, 숫자를 사용할 수 있으며 띄워쓰기가 불가능합니다. 한글 또는 영문을 최소 1자 포함해야 합니다.")
    private String nickname;
}
