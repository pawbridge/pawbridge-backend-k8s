package com.pawbridge.backend.user.internal.statistics.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice(assignableTypes = {TotalUserCountController.class, SignupStatisticsController.class})
public final class TotalUserCountExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TotalUserCountExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<TotalUserCountController.Response<Void>> serverError(RuntimeException failure) {
        LOG.error("User statistics query failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(new TotalUserCountController.Response<>(500, "서버 에러!", null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<TotalUserCountController.Response<Void>> missingParameter(MissingServletRequestParameterException failure) {
        return ResponseEntity.badRequest().body(new TotalUserCountController.Response<>(400,
                "필수 요청 파라미터 '" + failure.getParameterName() + "'(이)가 누락되었습니다.", null));
    }
}
