package com.pawbridge.backend.user.internal.nickname.web;

import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class UserNicknameController {
    private final ChangeUserNickname nicknames;

    public UserNicknameController(ChangeUserNickname nicknames) {
        this.nicknames = nicknames;
    }

    @PutMapping("/api/v1/users/me/nickname")
    public Response<Void> updateNickname(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody UpdateNicknameRequest request) {
        nicknames.change(userId, request.nickname());
        return new Response<>(200, "닉네임이 변경되었습니다.", null);
    }

    public record Response<T>(int code, String message, T data) {
    }
}
