package com.pawbridge.backend.user.internal.storemypage.web;

import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.Response;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StoreMyPageController.class)
public final class StoreMyPageExceptionHandler {
    private final UserNicknameExceptionHandler existing = new UserNicknameExceptionHandler();
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Response<Void>> missing(UserNotFoundException failure) { return existing.userNotFound(failure); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Response<Void>> unavailable(RuntimeException failure) { return existing.serverError(failure); }
}
