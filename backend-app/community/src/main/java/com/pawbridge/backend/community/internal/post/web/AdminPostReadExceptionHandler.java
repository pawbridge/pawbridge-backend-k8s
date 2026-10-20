package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=AdminPostReadController.class)
public final class AdminPostReadExceptionHandler {
    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing(PostNotFoundException failure) {
        return ResponseEntity.status(404).body(new ApiResponse<>(404, "게시글을 찾을 수 없습니다.", null));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> failure(RuntimeException failure) {
        return ResponseEntity.status(500).body(new ApiResponse<>(500, "서버 에러!", null));
    }
}
