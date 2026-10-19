package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NoteSendController.class)
public final class NoteSendExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(NoteSendExceptionHandler.class);

    @ExceptionHandler(NoteSendRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(NoteSendRejectedException failure) {
        return switch (failure.reason()) {
            case LOGIN -> error(401, "로그인이 필요합니다.");
            case INVALID_REQUEST -> error(400, "받는 사람과 쪽지 내용을 확인해 주세요.");
            case INVALID_BODY -> error(400, "쪽지는 1~5,000자로 작성해 주세요.");
            case INVALID_CONTEXT -> error(400, "연락할 글을 확인해 주세요.");
            case INACTIVE -> error(403, "이용할 수 없는 회원입니다.");
            case GONE -> error(410, "이 전송은 이미 처리되었으며 더 이상 보관되어 있지 않습니다.");
            case CONFLICT -> error(409, "같은 전송 키로 다른 내용을 보낼 수 없습니다.");
            case BLOCKED -> error(403, "차단된 상대에게 쪽지를 보낼 수 없습니다.");
            case RATE_LIMIT -> error(429, "쪽지 발송 한도에 도달했습니다. 잠시 후 다시 이용해 주세요.");
            case NOT_FOUND -> error(404, "쪽지를 찾을 수 없습니다.");
            case REPLY_MISMATCH -> error(400, "답장할 쪽지와 받는 사람이 일치하지 않습니다.");
            case CONTEXT_AUTHOR -> error(400, "연락할 글의 작성자를 확인해 주세요.");
        };
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
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
