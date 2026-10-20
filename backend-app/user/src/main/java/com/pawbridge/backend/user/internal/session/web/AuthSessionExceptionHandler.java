package com.pawbridge.backend.user.internal.session.web;

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=AuthSessionController.class)
public final class AuthSessionExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(AuthSessionExceptionHandler.class);
    private final UserNicknameExceptionHandler existing=new UserNicknameExceptionHandler();
    @ExceptionHandler(AuthSessionRejectedException.class)
    public ResponseEntity<?> rejected(AuthSessionRejectedException failure) {
        int code=failure.reason()==AuthSessionRejectedException.Reason.MISSING?404:401;
        return ResponseEntity.status(code).body(new AuthSessionController.Response<>(code,failure.getMessage(),null));
    }
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<?> missing(UserNotFoundException failure) { return existing.userNotFound(failure); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException failure) { return existing.validation(failure); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadable(HttpMessageNotReadableException failure) { return existing.unreadable(failure); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unexpected(RuntimeException failure) {
        // No exception messages, causes or token-bearing request details are logged.
        log.warn("Auth session request failed ({})",failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new AuthSessionController.Response<>(500,"서버 에러!",null));
    }
}
