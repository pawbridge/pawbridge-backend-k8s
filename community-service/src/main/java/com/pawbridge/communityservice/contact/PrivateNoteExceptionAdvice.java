package com.pawbridge.communityservice.contact;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQL exception text can contain private message values; never log it here. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PrivateNoteController.class, PrivateNoteDeletionController.class})
public class PrivateNoteExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .headers(failure.getHeaders())
                .body(Map.of("message", failure.getReason() == null ? "요청을 처리할 수 없습니다." : failure.getReason()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storageFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<?> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식과 로그인 정보를 확인해 주세요."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unexpectedFailure(RuntimeException failure) {
        // Preserve an operational signal, not the exception message, SQL parameters or stack.
        log.warn("Private note request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
