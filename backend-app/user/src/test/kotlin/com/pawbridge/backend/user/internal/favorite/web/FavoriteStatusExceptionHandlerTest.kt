package com.pawbridge.backend.user.internal.favorite.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException

class FavoriteStatusExceptionHandlerTest {
    @Test
    fun given__sensitive_failure_messages_and_suppressed_text__when__handling__then__log_only_type_and_source_location() {
        val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
        cause.stackTrace = arrayOf(
            StackTraceElement(
                "com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery",
                "isFavorite",
                "JdbcFavoriteStatusQuery.java",
                73,
            ),
        )
        val failure = IllegalStateException("Bearer synthetic-private-token synthetic-private-user-id=42", cause)
        failure.addSuppressed(RuntimeException("https://fixture.invalid/object?signature=synthetic-private-signature"))

        val event = handleAndCapture(failure)

        assertNull(event.throwableProxy)
        assertEquals(1, event.argumentArray.size)
        assertInstanceOf(String::class.java, event.argumentArray[0])
        assertTrue(event.formattedMessage.contains("java.lang.IllegalStateException"))
        assertTrue(event.formattedMessage.contains("java.sql.SQLException"))
        assertTrue(event.formattedMessage.contains("JdbcFavoriteStatusQuery.isFavorite:73"))
        assertFalse(event.formattedMessage.contains("synthetic-private"))
        assertFalse(event.formattedMessage.contains("Bearer "))
        assertFalse(event.formattedMessage.contains("jdbc:"))
    }

    @Test
    @Timeout(2)
    fun given__cyclic_causes_and_many_frames__when__handling__then__diagnostics_finish_with_bounded_output() {
        val failure = IllegalStateException("synthetic-private-user")
        val cause = IllegalArgumentException("synthetic-private-cause", failure)
        failure.initCause(cause)
        val frames = Array(100) {
            StackTraceElement(
                "com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery",
                "isFavorite",
                "JdbcFavoriteStatusQuery.java",
                73,
            )
        }
        failure.stackTrace = frames
        cause.stackTrace = frames

        val event = handleAndCapture(failure)

        assertNull(event.throwableProxy)
        assertTrue(event.formattedMessage.length < 4300)
        assertTrue(event.formattedMessage.lines().size < 30)
        assertFalse(event.formattedMessage.contains("synthetic-private"))
    }

    @Test
    fun given__deep_causes_and_long_source_metadata__when__handling__then__respect_depth_and_length_budgets() {
        val last = IOException("synthetic-private-cause")
        val fourth = SQLException("synthetic-private-SQL", last)
        val third = UnsupportedOperationException("synthetic-private-operation", fourth)
        val second = IllegalArgumentException("synthetic-private-input", third)
        val failure = IllegalStateException("synthetic-private-user", second)

        val deep = handleAndCapture(failure)

        assertTrue(deep.formattedMessage.contains("java.sql.SQLException"))
        assertFalse(deep.formattedMessage.contains("java.io.IOException"))
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "isFavorite", "User.java", 73),
        )
        val longMetadata = handleAndCapture(failure)
        assertTrue((longMetadata.argumentArray[0] as String).length <= 4096)
        assertFalse(longMetadata.formattedMessage.contains("synthetic-private"))
    }

    private fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
        val logger = LoggerFactory.getLogger(FavoriteStatusExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val response = FavoriteStatusExceptionHandler().serverError(failure)
            assertEquals(500, response.statusCode.value())
            assertEquals(FavoriteStatusController.Response<Void>(500, "서버 에러!", null), response.body)
            assertEquals(1, appender.list.size)
            return appender.list.first()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
