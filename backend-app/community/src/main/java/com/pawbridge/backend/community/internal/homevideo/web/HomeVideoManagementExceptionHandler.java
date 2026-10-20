package com.pawbridge.backend.community.internal.homevideo.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=HomeVideoManagementController.class)
public final class HomeVideoManagementExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<HomeVideoController.Response<Void>> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(new HomeVideoController.Response<>(failure.getStatusCode().value(),failure.getReason(),null));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<HomeVideoController.Response<Void>> invalid() {
        return ResponseEntity.badRequest().body(new HomeVideoController.Response<>(400,"영상 주소와 요청 형식을 확인해 주세요.",null));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<HomeVideoController.Response<Void>> failure(RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(HomeVideoManagementExceptionHandler.class).warn("Video curation request failed ({})",failure.getClass().getSimpleName());
        return ResponseEntity.status(503).body(new HomeVideoController.Response<>(503,"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",null));
    }
}
