package com.pawbridge.backend.animal.internal.chatbot.web;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException;
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Original chatbot envelopes, scoped to this controller; no question/history/key logging. */
@RestControllerAdvice(assignableTypes = ChatbotController.class)
public final class ChatbotExceptionHandler {
    @ExceptionHandler(ChatbotAnimalNotFoundException.class)
    public ResponseEntity<Failure> animalMissing(ChatbotAnimalNotFoundException failure) { return error(404, failure.getMessage()); }
    @ExceptionHandler(ChatbotRateLimitExceededException.class)
    public ResponseEntity<Failure> quota(ChatbotRateLimitExceededException failure) { return error(429, failure.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> invalid(IllegalArgumentException failure) { return error(400, failure.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Failure> validation(MethodArgumentNotValidException failure) {
        String message=failure.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField()+": "+field.getDefaultMessage()).collect(java.util.stream.Collectors.joining(", "));
        return error(400, message);
    }
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Failure> binding(BindException failure) { return error(400, failure.getBindingResult().getAllErrors().getFirst().getDefaultMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Failure> unreadable(HttpMessageNotReadableException failure) { return error(400, "요청 본문 형식이 올바르지 않습니다."); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Failure> database(DataAccessException failure) {
        if(PostgresqlRollbackCharsetViolation.matches(failure))return error(400, PostgresqlRollbackCharsetViolation.MESSAGE);
        return error(500, "데이터베이스 오류가 발생했습니다.");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected(Exception failure) {
        if(PostgresqlRollbackCharsetViolation.matches(failure))return error(400, PostgresqlRollbackCharsetViolation.MESSAGE);
        return error(500, "서버 내부 오류가 발생했습니다.");
    }
    private static ResponseEntity<Failure> error(int code, String message) { return ResponseEntity.status(code).body(new Failure(code, message, null)); }
    public record Failure(int code, String message, Object data) { }
}
