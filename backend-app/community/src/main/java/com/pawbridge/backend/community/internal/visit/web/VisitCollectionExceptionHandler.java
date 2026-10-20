package com.pawbridge.backend.community.internal.visit.web;

import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VisitCollectionController.class)
public final class VisitCollectionExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VisitCollectionExceptionHandler.class);
    @ExceptionHandler(VisitCollectionRejectedException.class)
    ResponseEntity<?> rejected(VisitCollectionRejectedException failure) {
        return error(failure.reason() == VisitCollectionRejectedException.Reason.COOKIE ? 409 : 400, failure.getMessage());
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException failure) { return error(failure.getStatusCode().value(), failure.getReason()); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> date(MethodArgumentTypeMismatchException failure) { return error(400, "날짜 형식을 확인해 주세요."); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable(DataAccessException failure) { return error(503, "방문 통계를 불러올 수 없습니다."); }
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<?> missing(org.springframework.web.bind.MissingServletRequestParameterException failure) {
        return ResponseEntity.badRequest().body(new VisitCollectionController.Response<>(400,
                "필수 요청 파라미터 '%s'(이)가 누락되었습니다.".formatted(failure.getParameterName()), null));
    }
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<?> runtime(RuntimeException failure) {
        log.warn("Visit collection request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new VisitCollectionController.Response<>(500, "서버 에러!", null));
    }
    private ResponseEntity<?> error(int code, String message) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore())
                .body(new VisitCollectionController.Response<>(code, message, null));
    }
}
