package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PostCreateController.class)
public final class PostCreateExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PostCreateExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(PostBoardNotAllowedException.class)
    public ResponseEntity<ApiResponse<Void>> boardNotAllowed(PostBoardNotAllowedException failure) {
        return badRequest(failure.getMessage());
    }

    @ExceptionHandler(UnsupportedPostCharactersException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedCharacters(UnsupportedPostCharactersException failure) {
        return badRequest(failure.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException failure) {
        return badRequest("필수 요청 파라미터 '" + failure.getParameterName() + "'(이)가 누락되었습니다.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> serverError(RuntimeException failure) {
        LOG.error("Unexpected post creation failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "서버 에러!", null));
    }

    // Exception messages, suppressed failures and request data can contain credentials or signed URLs.
    // Log only JVM type/source-location metadata, never the original Throwable or its text.
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

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, message, null));
    }
}
