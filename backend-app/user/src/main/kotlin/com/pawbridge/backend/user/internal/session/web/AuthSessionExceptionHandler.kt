package com.pawbridge.backend.user.internal.session.web

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AuthSessionController::class])
class AuthSessionExceptionHandler {

    /** Every rejection clears the cookie so the browser stops presenting a token that can no longer work. */
    @ExceptionHandler(AuthSessionRejectedException::class)
    fun rejected(failure: AuthSessionRejectedException): ResponseEntity<AuthSessionController.Response<Void>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString())
            .body(AuthSessionController.Response<Void>(401, failure.message, null))

    /** A transient failure keeps the cookie: the session may still be valid and the client can retry. */
    @ExceptionHandler(RuntimeException::class)
    fun unexpected(failure: RuntimeException): ResponseEntity<AuthSessionController.Response<Void>> {
        // No exception messages, causes or token-bearing request details are logged.
        log.warn("Auth session request failed ({})", failure.javaClass.simpleName)
        return ResponseEntity.internalServerError()
            .body(AuthSessionController.Response<Void>(500, "서버 에러!", null))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AuthSessionExceptionHandler::class.java)
    }
}
