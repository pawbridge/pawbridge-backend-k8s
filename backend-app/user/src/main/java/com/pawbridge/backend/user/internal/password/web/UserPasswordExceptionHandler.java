package com.pawbridge.backend.user.internal.password.web;

import com.pawbridge.backend.user.internal.password.application.InvalidCurrentPasswordException;
import com.pawbridge.backend.user.internal.password.application.OAuthUserCannotChangePasswordException;
import com.pawbridge.backend.user.internal.password.application.SamePasswordException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserPasswordController.class)
public final class UserPasswordExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(UserPasswordExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> userNotFound(UserNotFoundException failure) {
        return badRequest("존재하지 않는 회원입니다.");
    }

    @ExceptionHandler(OAuthUserCannotChangePasswordException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> socialAccount(OAuthUserCannotChangePasswordException failure) {
        return badRequest("소셜 로그인 사용자는 비밀번호를 변경할 수 없습니다.");
    }

    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> invalidCurrent(InvalidCurrentPasswordException failure) {
        return badRequest("현재 비밀번호가 일치하지 않습니다.");
    }

    @ExceptionHandler(SamePasswordException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> samePassword(SamePasswordException failure) {
        return badRequest("현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> validation(MethodArgumentNotValidException failure) {
        // Only field names and fixed constraint messages are returned; rejected values are secrets.
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> unreadable(HttpMessageNotReadableException failure) {
        return badRequest("요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<UserPasswordController.Response<Void>> serverError(RuntimeException failure) {
        LOG.error("Unexpected user password update failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError()
                .body(new UserPasswordController.Response<>(500, "서버 에러!", null));
    }

    private static ResponseEntity<UserPasswordController.Response<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(new UserPasswordController.Response<>(400, message, null));
    }

    private static String diagnosticTrace(Throwable failure) {
        var trace = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSES && visited.add(current); depth++) {
            trace.append(current.getClass().getName()).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, MAX_LOGGED_FRAMES); index++) {
                StackTraceElement frame = frames[index];
                trace.append("  at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(":").append(frame.getLineNumber()).append('\n');
            }
            current = current.getCause();
        }
        return trace.substring(0, Math.min(trace.length(), MAX_DIAGNOSTIC_LENGTH));
    }
}
