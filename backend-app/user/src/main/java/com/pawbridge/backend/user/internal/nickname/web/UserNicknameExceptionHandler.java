package com.pawbridge.backend.user.internal.nickname.web;

import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserNicknameController.class)
public final class UserNicknameExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(UserNicknameExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> userNotFound(UserNotFoundException failure) {
        return badRequest("존재하지 않는 회원입니다.");
    }

    @ExceptionHandler(NicknameDuplicateException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> duplicate(NicknameDuplicateException failure) {
        return ResponseEntity.status(409)
                .body(new UserNicknameController.Response<>(409, "이미 사용 중인 닉네임입니다.", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> validation(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> unreadable(HttpMessageNotReadableException failure) {
        return badRequest("요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<UserNicknameController.Response<Void>> serverError(RuntimeException failure) {
        // The application proxy commits after the store returns, so classify at the HTTP boundary.
        if (isRollbackCharsetViolation(failure)) {
            return badRequest("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.");
        }
        LOG.error("Unexpected user nickname update failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError()
                .body(new UserNicknameController.Response<>(500, "서버 에러!", null));
    }

    private static ResponseEntity<UserNicknameController.Response<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(new UserNicknameController.Response<>(400, message, null));
    }

    private static boolean isRollbackCharsetViolation(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof PSQLException sqlError) {
                ServerErrorMessage details = sqlError.getServerErrorMessage();
                if ("23514".equals(sqlError.getSQLState()) && details != null
                        && "pawbridge_user".equals(details.getSchema())
                        && details.getConstraint() != null
                        && details.getConstraint().startsWith("ck_rollback_charset_")) {
                    return true;
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            if (current instanceof SQLException sqlError && sqlError.getNextException() != null) {
                pending.addLast(sqlError.getNextException());
            }
        }
        return false;
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
