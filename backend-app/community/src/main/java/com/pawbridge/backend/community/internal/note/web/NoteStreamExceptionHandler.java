package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.NoteStreamRejectedException;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RestControllerAdvice(assignableTypes = NoteStreamController.class)
public final class NoteStreamExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(NoteStreamExceptionHandler.class);

    @ExceptionHandler(NoteStreamRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(NoteStreamRejectedException failure, HttpServletResponse response) {
        return switch (failure.reason()) {
            case LOGIN -> error(401, "로그인이 필요합니다.", response);
            case INACTIVE -> error(403, "이용할 수 없는 회원입니다.", response);
        };
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(failure.getStatusCode()).headers(failure.getHeaders())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getReason() == null ? "요청을 처리할 수 없습니다." : failure.getReason()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(HttpServletResponse response) {
        return error(400, "요청 형식과 로그인 정보를 확인해 주세요.", response);
    }

    @ExceptionHandler(ContactMemberUnavailableException.class)
    public ResponseEntity<Map<String, String>> memberUnavailable(HttpServletResponse response) {
        return error(503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", response);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable(HttpServletResponse response) {
        return error(503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", response);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unexpectedFailure(RuntimeException failure, HttpServletResponse response) {
        log.warn("Private note request failed ({})", failure.getClass().getSimpleName());
        return error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", response);
    }

    private static ResponseEntity<Map<String, String>> error(int code, String message, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
