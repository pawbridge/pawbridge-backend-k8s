package com.pawbridge.backend.community.internal.comment.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CommentReadController.class)
public final class CommentReadExceptionHandler {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<CommentReadController.Response<Void>> serverError(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CommentReadController.Response<>(500, "서버 에러!", null));
    }
}
