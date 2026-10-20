package com.pawbridge.backend.user.internal.deletion.web;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Direct controller/handler unit contracts, not MVC binding or ROLE_ADMIN/filter-chain evidence. */
class AdminUserDeletionControllerTest {
    @Test
    void givenAuthorizedControllerEntry__whenDeleting__thenInvokeOnceAndKeepTheOriginalSuccessEnvelope() {
        var ids = new java.util.ArrayList<Long>();
        var response = new AdminUserDeletionController(ids::add).delete(7L);

        assertEquals(java.util.List.of(7L), ids);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(new AdminUserDeletionController.Response<>(200, "회원이 삭제되었습니다.", null), response.getBody());
    }

    @Test
    void givenOnlyTheExistingAdminDelete__whenInspectingEntryAndAdvice__thenKeepTheExactMappingAndScopedHandler() throws Exception {
        var method = AdminUserDeletionController.class.getMethod("delete", Long.class);
        var mapping = method.getAnnotation(DeleteMapping.class);
        assertEquals("userId", method.getParameters()[0].getAnnotation(PathVariable.class).value());
        assertArrayEquals(new String[]{"/api/v1/admin/users/{userId}"}, mapping.value());
        var advice = AdminUserDeletionExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        assertArrayEquals(new Class<?>[]{AdminUserDeletionController.class}, advice.assignableTypes());
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void givenMissingCommunityOrFinalAccountFailure__whenTheActualControllerPropagatesToItsHandler__thenKeepOriginalStatusEnvelopeWithoutPrivateDetails(Failure kind) {
        RuntimeException failure = switch (kind) {
            case MISSING -> new UserNotFoundException();
            case COMMUNITY -> new MemberCleanupUnavailableException();
            case SQL -> new DataAccessResourceFailureException("private-sql-token");
            case COMMIT -> new TransactionSystemException("private-commit-body");
        };
        var controller = new AdminUserDeletionController(id -> { throw failure; });
        assertSame(failure, assertThrows(RuntimeException.class, () -> controller.delete(7L)));
        var handler = new AdminUserDeletionExceptionHandler();
        var logger = (Logger) LoggerFactory.getLogger(AdminUserDeletionExceptionHandler.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var response = switch (kind) {
                case MISSING -> handler.missing((UserNotFoundException) failure);
                case COMMUNITY -> handler.cleanupUnavailable((MemberCleanupUnavailableException) failure);
                case SQL, COMMIT -> handler.unexpected(failure);
            };
            int status = kind == Failure.MISSING ? 400 : kind == Failure.COMMUNITY ? 503 : 500;
            String message = kind == Failure.MISSING ? "존재하지 않는 회원입니다."
                    : kind == Failure.COMMUNITY ? "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요." : "서버 에러!";
            assertEquals(status, response.getStatusCode().value());
            assertEquals(new AdminUserDeletionController.Response<>(status, message, null), response.getBody());
            for (var event : logs.list) {
                assertNull(event.getThrowableProxy());
                assertFalse(event.getFormattedMessage().contains("private-"));
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private enum Failure { MISSING, COMMUNITY, SQL, COMMIT }
}
