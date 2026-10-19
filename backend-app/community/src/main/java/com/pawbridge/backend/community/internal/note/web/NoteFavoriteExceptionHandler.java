package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.NoteFavoriteRejectedException;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NoteFavoriteController.class)
public final class NoteFavoriteExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(NoteFavoriteExceptionHandler.class);

    @ExceptionHandler(NoteFavoriteRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(NoteFavoriteRejectedException failure) {
        return switch (failure.reason()) {
            case LOGIN -> error(401, "로그인이 필요합니다.");
            case INACTIVE -> error(403, "이용할 수 없는 회원입니다.");
            case NOT_FOUND -> error(404, "쪽지를 찾을 수 없습니다.");
        };
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidRequest() {
        return error(400, "요청 형식과 로그인 정보를 확인해 주세요.");
    }

    @ExceptionHandler(ContactMemberUnavailableException.class)
    public ResponseEntity<Map<String, String>> memberUnavailable() {
        return error(503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return error(503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unexpectedFailure(RuntimeException failure) {
        log.warn("Private note request failed ({})", failure.getClass().getSimpleName());
        return error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static ResponseEntity<Map<String, String>> error(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
