package com.pawbridge.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException

class CommentUpdateErrorLoggingTest {
    @Test
    fun given__failure_with_sensitive_messages__when__handling__then__log_type_and_location_without_sensitive_details() {
        val cause = SQLException("jdbc:postgresql://example.test/db?password=synthetic-db-password")
        cause.stackTrace = arrayOf(
            StackTraceElement(
                "com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter",
                "update",
                "JdbcCommentWriter.java",
                73,
            ),
        )
        val failure = IllegalStateException("Bearer synthetic-jwt-secret; synthetic-private-comment", cause)
        failure.addSuppressed(RuntimeException("https://example.test/object?signature=synthetic-signed-url"))

        val event = handleAndCapture(failure)

        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.throwableProxy).isNull()
        assertThat(event.argumentArray).hasSize(1).allSatisfy { argument ->
            assertThat(argument).isInstanceOf(String::class.java)
        }
        assertThat(event.formattedMessage)
            .contains(
                "Unexpected comment update failure",
                "java.lang.IllegalStateException",
                "java.sql.SQLException",
                "JdbcCommentWriter.update",
                "73",
            )
            .doesNotContain(
                "synthetic-jwt-secret",
                "synthetic-private-comment",
                "synthetic-db-password",
                "synthetic-signed-url",
                "Bearer ",
                "jdbc:",
            )
    }

    @Test
    @Timeout(2)
    fun given__cyclic_causes_and_many_frames__when__handling__then__diagnostics_remain_bounded() {
        val failure = IllegalStateException("synthetic-private-message")
        val cause = IllegalArgumentException("synthetic-private-cause", failure)
        failure.initCause(cause)
        val frames = Array(100) {
            StackTraceElement(
                "com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter",
                "update",
                "JdbcCommentWriter.java",
                73,
            )
        }
        failure.stackTrace = frames
        cause.stackTrace = frames

        val event = handleAndCapture(failure)

        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage).contains("IllegalStateException", "IllegalArgumentException")
            .doesNotContain("synthetic-private-message", "synthetic-private-cause")
        assertThat(event.formattedMessage.length).isLessThan(4300)
        assertThat(event.formattedMessage.lines().size).isLessThan(30)
    }

    @Test
    fun given__deep_cause_chain__when__handling__then__omit_causes_beyond_diagnostic_budget() {
        val last = IOException("synthetic-private-cause")
        val sql = SQLException("synthetic-private-sql", last)
        val unsupported = UnsupportedOperationException("synthetic-private-operation", sql)
        val invalid = IllegalArgumentException("synthetic-private-input", unsupported)
        val failure = IllegalStateException("synthetic-private-message", invalid)

        val event = handleAndCapture(failure)

        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage)
            .contains(
                "IllegalStateException",
                "IllegalArgumentException",
                "UnsupportedOperationException",
                "java.sql.SQLException",
            )
            .doesNotContain("java.io.IOException", "synthetic-private")
    }

    @Test
    fun given__long_source_metadata__when__handling__then__cap_diagnostic_text_length() {
        val failure = IllegalStateException("synthetic-private-message")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "update", "Comment.java", 73),
        )

        val event = handleAndCapture(failure)

        assertThat(event.throwableProxy).isNull()
        assertThat(event.argumentArray[0] as String).startsWith("java.lang.IllegalStateException")
            .hasSizeLessThanOrEqualTo(4096)
            .doesNotContain("synthetic-private-message")
    }

    companion object {
        private fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
            val logger = LoggerFactory.getLogger(CommentUpdateExceptionHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val response = CommentUpdateExceptionHandler().serverError(failure)
                assertThat(response.statusCode.value()).isEqualTo(500)
                assertThat(response.body).isEqualTo(CommentUpdateController.Response<Void?>(500, "서버 에러!", null))
                assertThat(appender.list).hasSize(1)
                return appender.list.first()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }
}
