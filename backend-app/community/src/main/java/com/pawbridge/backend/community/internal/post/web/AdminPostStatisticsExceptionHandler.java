package com.pawbridge.backend.community.internal.post.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AdminPostStatisticsController.class)
public final class AdminPostStatisticsExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AdminPostStatisticsExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> malformedDate() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingDate(MissingServletRequestParameterException failure) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,
                "필수 요청 파라미터 '" + failure.getParameterName() + "'(이)가 누락되었습니다.", null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> rejectedRange(ResponseStatusException failure) {
        int status = failure.getStatusCode().value();
        String message = failure.getStatusCode().is4xxClientError() ? failure.getReason() : null;
        return ResponseEntity.status(status).body(new ApiResponse<>(status,
                message == null ? "요청을 처리하지 못했습니다." : message, null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> serverError(RuntimeException failure) {
        log.error("Administrator post statistics failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "서버 에러!", null));
    }
}
