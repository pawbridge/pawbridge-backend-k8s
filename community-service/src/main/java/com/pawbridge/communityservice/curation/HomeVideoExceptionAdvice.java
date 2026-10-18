package com.pawbridge.communityservice.curation;

import com.pawbridge.communityservice.util.ResponseDTO;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = HomeVideoController.class)
public class HomeVideoExceptionAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException failure) {
        HttpStatus status = HttpStatus.valueOf(failure.getStatusCode().value());
        return ResponseEntity.status(status).body(ResponseDTO.errorWithMessage(status, failure.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, "영상 주소와 요청 형식을 확인해 주세요."));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> failure(RuntimeException failure) {
        // Do not log provider request URIs, keys or SQL parameters.
        org.slf4j.LoggerFactory.getLogger(HomeVideoExceptionAdvice.class)
                .warn("Video curation request failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ResponseDTO.errorWithMessage(HttpStatus.SERVICE_UNAVAILABLE, "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
