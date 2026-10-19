package com.pawbridge.backend.user.internal.statistics.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TotalUserCountController.class)
public final class TotalUserCountExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TotalUserCountExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<TotalUserCountController.Response<Void>> serverError(RuntimeException failure) {
        LOG.error("Total user count failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(new TotalUserCountController.Response<>(500, "서버 에러!", null));
    }
}
