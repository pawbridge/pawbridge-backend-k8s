package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
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

@RestControllerAdvice(assignableTypes = CommentUpdateController.class)
public final class CommentUpdateExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CommentUpdateExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> invalidContent(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new CommentUpdateController.Response<>(400, message, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> unreadableBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest()
                .body(new CommentUpdateController.Response<>(400, "요청 본문 형식이 올바르지 않습니다.", null));
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> commentNotFound(CommentNotFoundException failure) {
        return ResponseEntity.status(404)
                .body(new CommentUpdateController.Response<>(404, "댓글을 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(CommentNotOwnedException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> commentNotOwned(CommentNotOwnedException failure) {
        return ResponseEntity.status(403)
                .body(new CommentUpdateController.Response<>(403, "댓글에 접근할 권한이 없습니다.", null));
    }

    @ExceptionHandler(UnsupportedCommentCharactersException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> unsupportedCharacters(UnsupportedCommentCharactersException failure) {
        return ResponseEntity.badRequest()
                .body(new CommentUpdateController.Response<>(400, failure.getMessage(), null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<CommentUpdateController.Response<Void>> serverError(RuntimeException failure) {
        LOG.error("Unexpected comment update failure (exception messages omitted): {}", diagnosticTrace(failure));
        return ResponseEntity.internalServerError().body(new CommentUpdateController.Response<>(500, "서버 에러!", null));
    }

    // Reuse the existing post deletion advice's bounded metadata-only diagnostic contract.
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
