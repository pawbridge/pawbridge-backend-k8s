package com.pawbridge.backend.community.internal.post.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/** Multipart parsing can fail before a controller exists, so controller-scoped advice cannot handle it. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PostMultipartExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(PostMultipartExceptionHandler.class);

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> parsingFailure(MultipartException failure) {
        LOG.warn("Post multipart parsing failed (exception message omitted): {}",failure.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500,"서버 에러!",null));
    }
}
