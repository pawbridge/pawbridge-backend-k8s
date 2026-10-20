package com.pawbridge.backend.animal.internal.command.web;

import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandForbiddenException;
import org.springframework.dao.DataAccessException;
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AnimalCommandController.class)
public final class AnimalCommandExceptionHandler {
    @ExceptionHandler(AnimalCommandForbiddenException.class)
    public ResponseEntity<Failure> forbidden(AnimalCommandForbiddenException failure) { return error(403, failure.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> invalid(IllegalArgumentException failure) { return error(400, failure.getMessage()); }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Failure> binding(BindException failure) {
        return error(400, failure.getBindingResult().getAllErrors().getFirst().getDefaultMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Failure> validation(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        return error(400, message);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Failure> database(DataAccessException failure) {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) return error(400, PostgresqlRollbackCharsetViolation.MESSAGE);
        return error(500, "데이터베이스 오류가 발생했습니다.");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Failure> unreadable(Exception failure) { return error(400, "잘못된 요청 형식입니다."); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected(Exception failure) {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) return error(400, PostgresqlRollbackCharsetViolation.MESSAGE);
        return error(500, "서버 내부 오류가 발생했습니다.");
    }

    private static ResponseEntity<Failure> error(int code, String message) {
        return ResponseEntity.status(code).body(new Failure(code, message, null));
    }
    public record Failure(int code, String message, Object data) { }
}
