package com.pawbridge.backend.user.internal.adminupdate.web;

import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminUserUpdateController.class)
public final class AdminUserUpdateExceptionHandler {
    private final UserNicknameExceptionHandler existing = new UserNicknameExceptionHandler();

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> missing(UserNotFoundException failure) { return existing.userNotFound(failure); }

    @ExceptionHandler(NicknameDuplicateException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> duplicate(NicknameDuplicateException failure) { return existing.duplicate(failure); }

    @ExceptionHandler(ShelterApprovalRequiredException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> approval(ShelterApprovalRequiredException failure) {
        return ResponseEntity.badRequest().body(new UserNicknameController.Response<>(400,
                "보호소 권한은 담당자 신청 승인으로 부여해야 합니다.", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> validation(MethodArgumentNotValidException failure) { return existing.validation(failure); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> unreadable(HttpMessageNotReadableException failure) { return existing.unreadable(failure); }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> unexpected(RuntimeException failure) { return existing.serverError(failure); }
}
