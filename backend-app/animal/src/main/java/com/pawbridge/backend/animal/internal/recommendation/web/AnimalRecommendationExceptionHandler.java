package com.pawbridge.backend.animal.internal.recommendation.web;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AnimalRecommendationController.class)
public final class AnimalRecommendationExceptionHandler {
    @ExceptionHandler(AnimalNotFoundException.class)
    public ResponseEntity<Failure> missing(AnimalNotFoundException failure) { return error(404, failure.getMessage()); }

    @ExceptionHandler(RecommendationUnavailableException.class)
    public ResponseEntity<Failure> unavailable(RecommendationUnavailableException failure) { return error(503, failure.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> invalid(IllegalArgumentException failure) { return error(400, failure.getMessage()); }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Failure> database(DataAccessException failure) { return error(500, "데이터베이스 오류가 발생했습니다."); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected(Exception failure) { return error(500, "서버 내부 오류가 발생했습니다."); }

    private static ResponseEntity<Failure> error(int code, String message) {
        return ResponseEntity.status(code).body(new Failure(code, message, null));
    }
    public record Failure(int code, String message, Object data) { }
}
