package com.pawbridge.backend.user.internal.emailverification.web;

import com.pawbridge.backend.user.internal.emailverification.application.EmailSendingException;
import com.pawbridge.backend.user.internal.emailverification.application.EmailTemplateLoadException;
import com.pawbridge.backend.user.internal.emailverification.application.ExpiredCodeException;
import com.pawbridge.backend.user.internal.emailverification.application.InvalidCodeException;
import com.pawbridge.backend.user.internal.emailverification.application.TooManyAttemptsException;
import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController.Response;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordResetCodeInvalidException;
import com.pawbridge.backend.user.internal.passwordreset.web.PasswordResetController;
import com.pawbridge.backend.user.internal.signup.application.AdminRoleNotAllowedException;
import com.pawbridge.backend.user.internal.signup.application.EmailDuplicateException;
import com.pawbridge.backend.user.internal.signup.application.EmailNotVerifiedException;
import com.pawbridge.backend.user.internal.signup.application.InconsistentPasswordException;
import com.pawbridge.backend.user.internal.signup.application.RoleRequiredException;
import com.pawbridge.backend.user.internal.signup.application.ShelterApprovalRequiredException;
import com.pawbridge.backend.user.internal.signup.infrastructure.RollbackCharsetViolation;
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Original GlobalExceptionRestAdvice mapping for the e-mail, signup and password-reset routes only. */
@RestControllerAdvice(assignableTypes = {EmailVerificationController.class, LocalSignupController.class,
        PasswordResetController.class})
public final class LocalAuthExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LocalAuthExceptionHandler.class);
    private static final int MAX_LOGGED_CAUSES = 4;
    private static final int MAX_LOGGED_FRAMES = 6;
    private static final int MAX_DIAGNOSTIC_LENGTH = 4096;

    @ExceptionHandler(ExpiredCodeException.class)
    public ResponseEntity<Response<Void>> expired(ExpiredCodeException failure) {
        return error(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다.");
    }

    @ExceptionHandler(InvalidCodeException.class)
    public ResponseEntity<Response<Void>> invalid(InvalidCodeException failure) {
        return error(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않습니다.");
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Response<Void>> tooMany(TooManyAttemptsException failure) {
        return error(HttpStatus.TOO_MANY_REQUESTS, failure.kind() == TooManyAttemptsException.Kind.SEND
                ? "인증 코드 발송 횟수를 초과했습니다. 5분 후 다시 시도해주세요."
                : "인증 시도 횟수를 초과했습니다. 새로운 인증 코드를 요청해주세요.");
    }

    @ExceptionHandler(EmailSendingException.class)
    public ResponseEntity<Response<Void>> sending(EmailSendingException failure) {
        LOG.error("Verification mail sending failed: {}", diagnosticTrace(failure));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다.");
    }

    @ExceptionHandler(EmailTemplateLoadException.class)
    public ResponseEntity<Response<Void>> template(EmailTemplateLoadException failure) {
        LOG.error("Verification mail template failed to load: {}", diagnosticTrace(failure));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 템플릿 로드에 실패했습니다.");
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<Response<Void>> notVerified(EmailNotVerifiedException failure) {
        return error(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다.");
    }

    @ExceptionHandler(EmailDuplicateException.class)
    public ResponseEntity<Response<Void>> duplicate(EmailDuplicateException failure) {
        return error(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
    }

    @ExceptionHandler(InconsistentPasswordException.class)
    public ResponseEntity<Response<Void>> inconsistent(InconsistentPasswordException failure) {
        return error(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다.");
    }

    @ExceptionHandler(RoleRequiredException.class)
    public ResponseEntity<Response<Void>> roleRequired(RoleRequiredException failure) {
        return error(HttpStatus.BAD_REQUEST, "회원 유형을 선택해주세요.");
    }

    @ExceptionHandler(AdminRoleNotAllowedException.class)
    public ResponseEntity<Response<Void>> adminRole(AdminRoleNotAllowedException failure) {
        return error(HttpStatus.BAD_REQUEST, "관리자 계정은 회원가입으로 생성할 수 없습니다.");
    }

    @ExceptionHandler(ShelterApprovalRequiredException.class)
    public ResponseEntity<Response<Void>> shelterApproval(ShelterApprovalRequiredException failure) {
        return error(HttpStatus.BAD_REQUEST, "보호소 권한은 담당자 신청 승인으로 부여해야 합니다.");
    }

    @ExceptionHandler(PasswordResetCodeInvalidException.class)
    public ResponseEntity<Response<Void>> resetCodeInvalid(PasswordResetCodeInvalidException failure) {
        return error(HttpStatus.BAD_REQUEST, "비밀번호 재설정 인증코드가 유효하지 않습니다.");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Response<Void>> redisUnavailable(RedisConnectionFailureException failure) {
        LOG.error("Verification Redis connection failed: {}", diagnosticTrace(failure));
        return error(HttpStatus.SERVICE_UNAVAILABLE, "인증 서비스에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> validation(MethodArgumentNotValidException failure) {
        // Only field names and fixed constraint messages are returned; rejected values may be secrets.
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> unreadable(HttpMessageNotReadableException failure) {
        return error(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Response<Void>> serverError(RuntimeException failure) {
        if (RollbackCharsetViolation.matches(failure)) {
            return error(HttpStatus.BAD_REQUEST, RollbackCharsetViolation.MESSAGE);
        }
        LOG.error("Unexpected local auth failure (exception messages omitted): {}", diagnosticTrace(failure));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 에러!");
    }

    private static ResponseEntity<Response<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new Response<>(status.value(), message, null));
    }

    private static String diagnosticTrace(Throwable failure) {
        var trace = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSES && visited.add(current); depth++) {
            trace.append(current.getClass().getName()).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, MAX_LOGGED_FRAMES); index++) {
                StackTraceElement frame = frames[index];
                trace.append("  at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(":").append(frame.getLineNumber()).append('\n');
            }
            current = current.getCause();
        }
        return trace.substring(0, Math.min(trace.length(), MAX_DIAGNOSTIC_LENGTH));
    }
}
