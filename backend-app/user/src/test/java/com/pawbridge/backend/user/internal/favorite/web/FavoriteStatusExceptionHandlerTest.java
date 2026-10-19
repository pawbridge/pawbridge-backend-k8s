package com.pawbridge.backend.user.internal.favorite.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

class FavoriteStatusExceptionHandlerTest {
    @Test
    void given__sensitive_failure_messages_and_suppressed_text__when__handling__then__log_only_type_and_source_location() {
        var cause = new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery",
                        "isFavorite", "JdbcFavoriteStatusQuery.java", 73)});
        var failure = new IllegalStateException("Bearer synthetic-private-token synthetic-private-user-id=42", cause);
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/object?signature=synthetic-private-signature"));

        ILoggingEvent event = handleAndCapture(failure);

        assertNull(event.getThrowableProxy());
        assertEquals(1, event.getArgumentArray().length);
        assertInstanceOf(String.class, event.getArgumentArray()[0]);
        assertTrue(event.getFormattedMessage().contains("java.lang.IllegalStateException"));
        assertTrue(event.getFormattedMessage().contains("java.sql.SQLException"));
        assertTrue(event.getFormattedMessage().contains("JdbcFavoriteStatusQuery.isFavorite:73"));
        assertFalse(event.getFormattedMessage().contains("synthetic-private"));
        assertFalse(event.getFormattedMessage().contains("Bearer "));
        assertFalse(event.getFormattedMessage().contains("jdbc:"));
    }

    @Test
    @Timeout(2)
    void given__cyclic_causes_and_many_frames__when__handling__then__diagnostics_finish_with_bounded_output() {
        var failure = new IllegalStateException("synthetic-private-user");
        var cause = new IllegalArgumentException("synthetic-private-cause", failure);
        failure.initCause(cause);
        var frames = new StackTraceElement[100];
        Arrays.fill(frames, new StackTraceElement("com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery",
                "isFavorite", "JdbcFavoriteStatusQuery.java", 73));
        failure.setStackTrace(frames);
        cause.setStackTrace(frames);

        ILoggingEvent event = handleAndCapture(failure);

        assertNull(event.getThrowableProxy());
        assertTrue(event.getFormattedMessage().length() < 4300);
        assertTrue(event.getFormattedMessage().lines().count() < 30);
        assertFalse(event.getFormattedMessage().contains("synthetic-private"));
    }

    @Test
    void given__deep_causes_and_long_source_metadata__when__handling__then__respect_depth_and_length_budgets() {
        var last = new java.io.IOException("synthetic-private-cause");
        var fourth = new SQLException("synthetic-private-SQL", last);
        var third = new UnsupportedOperationException("synthetic-private-operation", fourth);
        var second = new IllegalArgumentException("synthetic-private-input", third);
        var failure = new IllegalStateException("synthetic-private-user", second);
        ILoggingEvent deep = handleAndCapture(failure);
        assertTrue(deep.getFormattedMessage().contains("java.sql.SQLException"));
        assertFalse(deep.getFormattedMessage().contains("java.io.IOException"));

        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "isFavorite", "User.java", 73)});
        ILoggingEvent longMetadata = handleAndCapture(failure);
        assertTrue(((String) longMetadata.getArgumentArray()[0]).length() <= 4096);
        assertFalse(longMetadata.getFormattedMessage().contains("synthetic-private"));
    }

    private static ILoggingEvent handleAndCapture(RuntimeException failure) {
        Logger logger = (Logger) LoggerFactory.getLogger(FavoriteStatusExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new FavoriteStatusExceptionHandler().serverError(failure);
            assertEquals(500, response.getStatusCode().value());
            assertEquals(new FavoriteStatusController.Response<Void>(500, "서버 에러!", null), response.getBody());
            assertEquals(1, appender.list.size());
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
