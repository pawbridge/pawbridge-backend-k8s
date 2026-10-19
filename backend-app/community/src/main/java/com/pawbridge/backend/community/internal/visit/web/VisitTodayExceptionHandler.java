package com.pawbridge.backend.community.internal.visit.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VisitTodayController.class)
public final class VisitTodayExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(VisitTodayExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<VisitTodayController.Response<Void>> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(new VisitTodayController.Response<>(503, "방문 통계를 불러올 수 없습니다.", null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<VisitTodayController.Response<Void>> serverError(RuntimeException failure) {
        log.warn("Visit today request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new VisitTodayController.Response<>(500, "서버 에러!", null));
    }
}
