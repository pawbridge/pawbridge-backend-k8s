package com.pawbridge.backend.user.internal.nickname.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

class UserNicknameExceptionHandlerTest {
    private final UserNicknameExceptionHandler handler = new UserNicknameExceptionHandler();

    @Test
    void given__structured_user_charset_failure_wrapped_at_commit__when__handling__then__fixed_charset_bad_request_is_returned() {
        var failure = new TransactionSystemException("synthetic private commit text",
                error("23514", "pawbridge_user", "ck_rollback_charset_users"));

        var response = handler.serverError(failure);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(new UserNicknameController.Response<Void>(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", null),
                response.getBody());
    }

    @Test
    void given__user_charset_metadata_in_jdbc_next_exception__when__handling__then__nested_batch_metadata_is_recognized() {
        var batch = new BatchUpdateException("synthetic private batch text", "23514", new int[0]);
        batch.setNextException(error("23514", "pawbridge_user", "ck_rollback_charset_users"));

        var response = handler.serverError(new DataIntegrityViolationException("synthetic private SQL", batch));

        assertEquals(400, response.getStatusCode().value());
    }

    @ParameterizedTest
    @MethodSource("nonCharsetFailures")
    void given__other_database_or_unstructured_failure__when__handling__then__safe_server_error_is_preserved(RuntimeException failure) {
        var response = handler.serverError(failure);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(new UserNicknameController.Response<Void>(500, "서버 에러!", null), response.getBody());
    }

    private static Stream<RuntimeException> nonCharsetFailures() {
        return Stream.of(
                new DataIntegrityViolationException("synthetic", error("23505", "pawbridge_user", "ck_rollback_charset_users")),
                new DataIntegrityViolationException("synthetic", error("23514", "pawbridge_community", "ck_rollback_charset_users")),
                new DataIntegrityViolationException("synthetic", error("23514", "pawbridge_user", "business_constraint")),
                new DataIntegrityViolationException("23514 pawbridge_user ck_rollback_charset_users",
                        new SQLException("23514 pawbridge_user ck_rollback_charset_users", "23514")),
                new TransactionSystemException("synthetic private commit failure"));
    }

    @Test
    void given__sensitive_failure_messages_and_suppressed_text__when__handling__then__logs_contain_only_type_and_source_location() {
        var cause = new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore",
                        "updateNickname", "JdbcUserNicknameStore.java", 73)});
        var failure = new IllegalStateException("Bearer synthetic-private-token synthetic-private-member", cause);
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/object?signature=synthetic-private-signature"));

        ILoggingEvent event = handleAndCapture(failure);

        assertNull(event.getThrowableProxy());
        assertEquals(1, event.getArgumentArray().length);
        assertInstanceOf(String.class, event.getArgumentArray()[0]);
        String message = event.getFormattedMessage();
        assertTrue(message.contains("java.lang.IllegalStateException"));
        assertTrue(message.contains("java.sql.SQLException"));
        assertTrue(message.contains("JdbcUserNicknameStore.updateNickname:73"));
        assertFalse(message.contains("synthetic-private"));
        assertFalse(message.contains("Bearer "));
        assertFalse(message.contains("jdbc:"));
    }

    @Test
    @Timeout(2)
    void given__cyclic_causes_with_many_frames__when__handling__then__classification_and_diagnostics_finish_with_bounded_output() {
        var failure = new IllegalStateException("synthetic-private-member");
        var cause = new SQLException("synthetic-private-cause", "23514");
        failure.initCause(cause);
        cause.initCause(failure);
        var frames = new StackTraceElement[100];
        Arrays.fill(frames, new StackTraceElement("com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore",
                "updateNickname", "JdbcUserNicknameStore.java", 73));
        failure.setStackTrace(frames);
        cause.setStackTrace(frames);

        ILoggingEvent event = handleAndCapture(failure);

        assertNull(event.getThrowableProxy());
        assertTrue(event.getFormattedMessage().length() < 4300);
        assertTrue(event.getFormattedMessage().lines().count() < 30);
        assertFalse(event.getFormattedMessage().contains("synthetic-private"));
    }

    @Test
    void given__deep_cause_chain_and_long_source_metadata__when__handling__then__diagnostic_depth_and_length_budgets_are_respected() {
        var last = new java.io.IOException("synthetic-private-cause");
        var fourth = new SQLException("synthetic-private-SQL", last);
        var third = new UnsupportedOperationException("synthetic-private-operation", fourth);
        var second = new IllegalArgumentException("synthetic-private-input", third);
        var failure = new IllegalStateException("synthetic-private-member", second);
        ILoggingEvent deep = handleAndCapture(failure);
        assertTrue(deep.getFormattedMessage().contains("java.sql.SQLException"));
        assertFalse(deep.getFormattedMessage().contains("java.io.IOException"));

        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "updateNickname", "User.java", 73)});
        ILoggingEvent longMetadata = handleAndCapture(failure);
        assertTrue(((String) longMetadata.getArgumentArray()[0]).length() <= 4096);
        assertFalse(longMetadata.getFormattedMessage().contains("synthetic-private"));
    }

    private ILoggingEvent handleAndCapture(RuntimeException failure) {
        Logger logger = (Logger) LoggerFactory.getLogger(UserNicknameExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = handler.serverError(failure);
            assertEquals(500, response.getStatusCode().value());
            assertEquals(new UserNicknameController.Response<Void>(500, "서버 에러!", null), response.getBody());
            assertEquals(1, appender.list.size());
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static PSQLException error(String state, String schema, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0Msynthetic-private\0s" + schema + "\0n" + constraint + "\0"));
    }
}
