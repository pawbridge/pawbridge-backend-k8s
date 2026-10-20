package com.pawbridge.backend.user.internal.deletion.web;

import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Only the existing admin DELETE; Boot must enforce verified ROLE_ADMIN before this controller. */
@RestController
public final class AdminUserDeletionController {
    private final UserDeletion deletion;

    public AdminUserDeletionController(UserDeletion deletion) {
        this.deletion = deletion;
    }

    @DeleteMapping("/api/v1/admin/users/{userId}")
    public ResponseEntity<Response<Void>> delete(@PathVariable("userId") Long userId) {
        deletion.delete(userId);
        return ResponseEntity.ok(new Response<>(200, "회원이 삭제되었습니다.", null));
    }

    public record Response<T>(int code, String message, T data) { }
}
