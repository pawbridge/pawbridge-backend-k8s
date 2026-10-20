package com.pawbridge.backend.user.internal.profile.web;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {UserProfileController.class, AdminUserDetailController.class})
public final class UserProfileExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(UserProfileExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<UserProfileController.Response<Void>> userNotFound(UserNotFoundException failure) {
        return ResponseEntity.badRequest()
                .body(new UserProfileController.Response<>(400, "존재하지 않는 회원입니다.", null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<UserProfileController.Response<Void>> serverError(RuntimeException failure) {
        LOG.error("Unexpected user profile lookup failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError().body(new UserProfileController.Response<>(500, "서버 에러!", null));
    }

    // Match the existing comment update advice's bounded metadata-only diagnostic contract.
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
