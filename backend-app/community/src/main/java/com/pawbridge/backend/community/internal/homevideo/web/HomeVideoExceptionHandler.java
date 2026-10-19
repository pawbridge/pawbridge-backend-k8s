package com.pawbridge.backend.community.internal.homevideo.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = HomeVideoController.class)
public final class HomeVideoExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(HomeVideoExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<HomeVideoController.Response<Void>> failure(RuntimeException failure) {
        log.warn("Video curation request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HomeVideoController.Response<>(503,
                        "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", null));
    }
}
