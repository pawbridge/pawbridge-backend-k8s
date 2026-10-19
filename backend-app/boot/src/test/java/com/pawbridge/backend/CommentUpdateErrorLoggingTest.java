package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController;
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

class CommentUpdateErrorLoggingTest {
    @Test
    void given__failure_with_sensitive_messages__when__handling__then__log_type_and_location_without_sensitive_details() {
        var cause = new SQLException("jdbc:postgresql://example.test/db?password=synthetic-db-password");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter",
                        "update", "JdbcCommentWriter.java", 73)});
        var failure = new IllegalStateException("Bearer synthetic-jwt-secret; synthetic-private-comment", cause);
        failure.addSuppressed(new RuntimeException("https://example.test/object?signature=synthetic-signed-url"));

        ILoggingEvent event = handleAndCapture(failure);

        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getArgumentArray()).hasSize(1).allSatisfy(argument -> assertThat(argument).isInstanceOf(String.class));
        assertThat(event.getFormattedMessage())
                .contains("Unexpected comment update failure", "java.lang.IllegalStateException", "java.sql.SQLException",
                        "JdbcCommentWriter.update", "73")
                .doesNotContain("synthetic-jwt-secret", "synthetic-private-comment", "synthetic-db-password",
                        "synthetic-signed-url", "Bearer ", "jdbc:");
    }

    @Test
    @Timeout(2)
    void given__cyclic_causes_and_many_frames__when__handling__then__diagnostics_remain_bounded() {
        var failure = new IllegalStateException("synthetic-private-message");
        var cause = new IllegalArgumentException("synthetic-private-cause", failure);
        failure.initCause(cause);
        var frames = new StackTraceElement[100];
        Arrays.fill(frames, new StackTraceElement("com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter",
                "update", "JdbcCommentWriter.java", 73));
        failure.setStackTrace(frames);
        cause.setStackTrace(frames);

        ILoggingEvent event = handleAndCapture(failure);

        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).contains("IllegalStateException", "IllegalArgumentException")
                .doesNotContain("synthetic-private-message", "synthetic-private-cause");
        assertThat(event.getFormattedMessage().length()).isLessThan(4300);
        assertThat(event.getFormattedMessage().lines().count()).isLessThan(30);
    }

    @Test
    void given__deep_cause_chain__when__handling__then__omit_causes_beyond_diagnostic_budget() {
        var last = new java.io.IOException("synthetic-private-cause");
        var sql = new SQLException("synthetic-private-sql", last);
        var unsupported = new UnsupportedOperationException("synthetic-private-operation", sql);
        var invalid = new IllegalArgumentException("synthetic-private-input", unsupported);
        var failure = new IllegalStateException("synthetic-private-message", invalid);

        ILoggingEvent event = handleAndCapture(failure);

        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).contains("IllegalStateException", "IllegalArgumentException",
                "UnsupportedOperationException", "java.sql.SQLException")
                .doesNotContain("java.io.IOException", "synthetic-private");
    }

    @Test
    void given__long_source_metadata__when__handling__then__cap_diagnostic_text_length() {
        var failure = new IllegalStateException("synthetic-private-message");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "update", "Comment.java", 73)});

        ILoggingEvent event = handleAndCapture(failure);

        assertThat(event.getThrowableProxy()).isNull();
        assertThat((String) event.getArgumentArray()[0]).startsWith("java.lang.IllegalStateException")
                .hasSizeLessThanOrEqualTo(4096).doesNotContain("synthetic-private-message");
    }

    private static ILoggingEvent handleAndCapture(RuntimeException failure) {
        Logger logger = (Logger) LoggerFactory.getLogger(CommentUpdateExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new CommentUpdateExceptionHandler().serverError(failure);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isEqualTo(new CommentUpdateController.Response<Void>(500, "서버 에러!", null));
            assertThat(appender.list).hasSize(1);
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
