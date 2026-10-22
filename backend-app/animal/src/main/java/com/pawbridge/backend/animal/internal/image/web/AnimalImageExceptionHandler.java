package com.pawbridge.backend.animal.internal.image.web;

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageForbiddenException;
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageTooLargeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Original GlobalExceptionHandler cases reachable from the image routes, scoped to this controller. */
@RestControllerAdvice(assignableTypes = AnimalImageController.class)
public final class AnimalImageExceptionHandler {
    @ExceptionHandler(AnimalImageForbiddenException.class)
    public ResponseEntity<Failure> forbidden(AnimalImageForbiddenException failure) { return error(403, failure.getMessage()); }

    @ExceptionHandler(AnimalImageTooLargeException.class)
    public ResponseEntity<Failure> tooLarge(AnimalImageTooLargeException failure) { return error(413, failure.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> invalid(IllegalArgumentException failure) { return error(400, failure.getMessage()); }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Failure> missing(MissingServletRequestParameterException failure) {
        return error(400, String.format("필수 요청 파라미터 '%s'(이)가 누락되었습니다.", failure.getParameterName()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Failure> database(DataAccessException failure) { return error(500, "데이터베이스 오류가 발생했습니다."); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected(Exception failure) { return error(500, "서버 내부 오류가 발생했습니다."); }

    private static ResponseEntity<Failure> error(int code, String message) {
        return ResponseEntity.status(code).body(new Failure(code, message, null));
    }
    public record Failure(int code, String message, Object data) { }
}
