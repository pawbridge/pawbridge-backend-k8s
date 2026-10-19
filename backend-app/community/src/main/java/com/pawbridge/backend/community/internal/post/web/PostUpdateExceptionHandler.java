package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException;
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PostUpdateController.class)
public final class PostUpdateExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PostUpdateExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> postNotFound(PostNotFoundException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse<>(404, "게시글을 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(PostNotOwnedException.class)
    public ResponseEntity<ApiResponse<Void>> postNotOwned(PostNotOwnedException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse<>(403, "게시글에 접근할 권한이 없습니다.", null));
    }

    @ExceptionHandler(UnsupportedPostCharactersException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedCharacters(UnsupportedPostCharactersException failure) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,failure.getMessage(),null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> serverError(RuntimeException failure) {
        LOG.error("Unexpected post update failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "서버 에러!", null));
    }

    // Match the existing creation advice's bounded metadata-only diagnostic contract.
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
