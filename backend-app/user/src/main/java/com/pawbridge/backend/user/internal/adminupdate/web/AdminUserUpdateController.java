package com.pawbridge.backend.user.internal.adminupdate.web;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AdminUserUpdateController {
    private final AdminUserUpdate members;

    public AdminUserUpdateController(AdminUserUpdate members) { this.members = members; }

    @PutMapping("/api/v1/admin/users/{userId}")
    public UserNicknameController.Response<Void> update(@PathVariable("userId") Long userId,
            @Valid @RequestBody AdminUserUpdateRequest request) {
        members.update(userId, new AdminUserUpdate.Changes(request.nickname(), request.role(), request.careRegNo()));
        return new UserNicknameController.Response<>(200, "회원 정보가 수정되었습니다.", null);
    }
}
