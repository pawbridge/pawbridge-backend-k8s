package com.pawbridge.backend.user.internal.nickname.web

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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionSystemException
import java.io.IOException
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.util.stream.Stream

class UserNicknameExceptionHandlerTest {
    private val handler = UserNicknameExceptionHandler()

    @Test
    fun given__structured_user_charset_failure_wrapped_at_commit__when__handling__then__fixed_charset_bad_request_is_returned() {
        val failure = TransactionSystemException(
            "synthetic private commit text",
            error("23514", "pawbridge_user", "ck_rollback_charset_users"),
        )

        val response = handler.serverError(failure)

        assertEquals(400, response.statusCode.value())
        assertEquals(
            UserNicknameController.Response<Void>(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", null),
            response.body,
        )
    }

    @Test
    fun given__user_charset_metadata_in_jdbc_next_exception__when__handling__then__nested_batch_metadata_is_recognized() {
        val batch = BatchUpdateException("synthetic private batch text", "23514", IntArray(0))
        batch.nextException = error("23514", "pawbridge_user", "ck_rollback_charset_users")

        val response = handler.serverError(DataIntegrityViolationException("synthetic private SQL", batch))

        assertEquals(400, response.statusCode.value())
    }

    @ParameterizedTest
    @MethodSource("nonCharsetFailures")
    fun given__other_database_or_unstructured_failure__when__handling__then__safe_server_error_is_preserved(
        failure: RuntimeException,
    ) {
        val response = handler.serverError(failure)

        assertEquals(500, response.statusCode.value())
        assertEquals(UserNicknameController.Response<Void>(500, "서버 에러!", null), response.body)
    }

    @Test
    fun given__sensitive_failure_messages_and_suppressed_text__when__handling__then__logs_contain_only_type_and_source_location() {
        val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
        cause.stackTrace = arrayOf(
            StackTraceElement(
                "com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore",
                "updateNickname",
                "JdbcUserNicknameStore.java",
                73,
            ),
        )
        val failure = IllegalStateException("Bearer synthetic-private-token synthetic-private-member", cause)
        failure.addSuppressed(RuntimeException("https://fixture.invalid/object?signature=synthetic-private-signature"))

        val event = handleAndCapture(failure)

        assertNull(event.throwableProxy)
        assertEquals(1, event.argumentArray.size)
        assertInstanceOf(String::class.java, event.argumentArray[0])
        val message = event.formattedMessage
        assertTrue(message.contains("java.lang.IllegalStateException"))
        assertTrue(message.contains("java.sql.SQLException"))
        assertTrue(message.contains("JdbcUserNicknameStore.updateNickname:73"))
        assertFalse(message.contains("synthetic-private"))
        assertFalse(message.contains("Bearer "))
        assertFalse(message.contains("jdbc:"))
    }

    @Test
    @Timeout(2)
    fun given__cyclic_causes_with_many_frames__when__handling__then__classification_and_diagnostics_finish_with_bounded_output() {
        val failure = IllegalStateException("synthetic-private-member")
        val cause = SQLException("synthetic-private-cause", "23514")
        failure.initCause(cause)
        cause.initCause(failure)
        val frames = Array(100) {
            StackTraceElement(
                "com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore",
                "updateNickname",
                "JdbcUserNicknameStore.java",
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
    fun given__deep_cause_chain_and_long_source_metadata__when__handling__then__diagnostic_depth_and_length_budgets_are_respected() {
        val last = IOException("synthetic-private-cause")
        val fourth = SQLException("synthetic-private-SQL", last)
        val third = UnsupportedOperationException("synthetic-private-operation", fourth)
        val second = IllegalArgumentException("synthetic-private-input", third)
        val failure = IllegalStateException("synthetic-private-member", second)

        val deep = handleAndCapture(failure)

        assertTrue(deep.formattedMessage.contains("java.sql.SQLException"))
        assertFalse(deep.formattedMessage.contains("java.io.IOException"))
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "updateNickname", "User.java", 73),
        )
        val longMetadata = handleAndCapture(failure)
        assertTrue((longMetadata.argumentArray[0] as String).length <= 4096)
        assertFalse(longMetadata.formattedMessage.contains("synthetic-private"))
    }

    private fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
        val logger = LoggerFactory.getLogger(UserNicknameExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val response = handler.serverError(failure)
            assertEquals(500, response.statusCode.value())
            assertEquals(UserNicknameController.Response<Void>(500, "서버 에러!", null), response.body)
            assertEquals(1, appender.list.size)
            return appender.list.first()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    companion object {
        @JvmStatic
        fun nonCharsetFailures(): Stream<RuntimeException> = Stream.of(
            DataIntegrityViolationException("synthetic", error("23505", "pawbridge_user", "ck_rollback_charset_users")),
            DataIntegrityViolationException("synthetic", error("23514", "pawbridge_community", "ck_rollback_charset_users")),
            DataIntegrityViolationException("synthetic", error("23514", "pawbridge_user", "business_constraint")),
            DataIntegrityViolationException(
                "23514 pawbridge_user ck_rollback_charset_users",
                SQLException("23514 pawbridge_user ck_rollback_charset_users", "23514"),
            ),
            TransactionSystemException("synthetic private commit failure"),
        )

        private fun error(state: String, schema: String, constraint: String): PSQLException {
            return PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C" + state + "\u0000Msynthetic-private\u0000s" + schema + "\u0000n" + constraint + "\u0000",
                ),
            )
        }
    }
}
