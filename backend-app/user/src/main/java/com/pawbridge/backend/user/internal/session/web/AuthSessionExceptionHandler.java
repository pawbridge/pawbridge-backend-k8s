package com.pawbridge.backend.user.internal.session.web;

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthSessionController.class)
public final class AuthSessionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthSessionExceptionHandler.class);

    /** Every rejection clears the cookie so the browser stops presenting a token that can no longer work. */
    @ExceptionHandler(AuthSessionRejectedException.class)
    public ResponseEntity<AuthSessionController.Response<Void>> rejected(AuthSessionRejectedException failure) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString())
                .body(new AuthSessionController.Response<>(401, failure.getMessage(), null));
    }

    /** A transient failure keeps the cookie: the session may still be valid and the client can retry. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AuthSessionController.Response<Void>> unexpected(RuntimeException failure) {
        // No exception messages, causes or token-bearing request details are logged.
        log.warn("Auth session request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(new AuthSessionController.Response<>(500, "서버 에러!", null));
    }
}
