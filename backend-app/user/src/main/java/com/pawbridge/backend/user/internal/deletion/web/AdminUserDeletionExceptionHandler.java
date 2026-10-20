package com.pawbridge.backend.user.internal.deletion.web;

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice(assignableTypes = AdminUserDeletionController.class)
public final class AdminUserDeletionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AdminUserDeletionExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<AdminUserDeletionController.Response<Void>> missing(UserNotFoundException failure) {
        return response(400, "존재하지 않는 회원입니다.");
    }

    @ExceptionHandler(MemberCleanupUnavailableException.class)
    public ResponseEntity<AdminUserDeletionController.Response<Void>> cleanupUnavailable(MemberCleanupUnavailableException failure) {
        return response(503, "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AdminUserDeletionController.Response<Void>> unexpected(RuntimeException failure) {
        log.error("Admin user deletion failed ({})", failure.getClass().getSimpleName());
        return response(500, "서버 에러!");
    }

    private static ResponseEntity<AdminUserDeletionController.Response<Void>> response(int status, String message) {
        return ResponseEntity.status(status).body(new AdminUserDeletionController.Response<>(status, message, null));
    }
}
