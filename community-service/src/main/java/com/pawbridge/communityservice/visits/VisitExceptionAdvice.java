package com.pawbridge.communityservice.visits;

import com.pawbridge.communityservice.util.ResponseDTO;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=VisitController.class)
public class VisitExceptionAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException failure) {
        HttpStatus status=HttpStatus.valueOf(failure.getStatusCode().value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ResponseDTO.errorWithMessage(status,failure.getReason()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST,"날짜 형식을 확인해 주세요."));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(ResponseDTO.errorWithMessage(HttpStatus.SERVICE_UNAVAILABLE,"방문 통계를 불러올 수 없습니다."));
    }
}
