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
    fun `구조화된 사용자 문자셋 실패가 커밋에서 감싸졌을 때__처리하면__고정된 문자셋 400 응답을 돌려준다`() {
        // given
        val failure = TransactionSystemException(
            "synthetic private commit text",
            error("23514", "pawbridge_user", "ck_rollback_charset_users"),
        )

        // when
        val response = handler.serverError(failure)

        // then
        assertEquals(400, response.statusCode.value())
        assertEquals(
            UserNicknameController.Response<Void>(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", null),
            response.body,
        )
    }

    @Test
    fun `사용자 문자셋 메타데이터가 JDBC 다음 예외에 있을 때__처리하면__중첩된 배치 메타데이터를 인식한다`() {
        // given
        val batch = BatchUpdateException("synthetic private batch text", "23514", IntArray(0))
        batch.nextException = error("23514", "pawbridge_user", "ck_rollback_charset_users")

        // when
        val response = handler.serverError(DataIntegrityViolationException("synthetic private SQL", batch))

        // then
        assertEquals(400, response.statusCode.value())
    }

    @ParameterizedTest
    @MethodSource("nonCharsetFailures")
    fun `문자셋이 아닌 다른 데이터베이스 실패나 구조 없는 실패일 때__처리하면__안전한 서버 오류를 유지한다`(
        failure: RuntimeException,
    ) {
        // given
        // when
        val response = handler.serverError(failure)

        // then
        assertEquals(500, response.statusCode.value())
        assertEquals(UserNicknameController.Response<Void>(500, "서버 에러!", null), response.body)
    }

    @Test
    fun `민감한 실패 메시지와 억제된 예외 문구가 있을 때__처리하면__로그에는 타입과 소스 위치만 담긴다`() {
        // given
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


        // when
        val event = handleAndCapture(failure)

        // then
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
    fun `순환 원인과 많은 프레임이 있을 때__처리하면__분류와 진단이 제한된 출력으로 끝난다`() {
        // given
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

        // when
        val event = handleAndCapture(failure)

        // then
        assertNull(event.throwableProxy)
        assertTrue(event.formattedMessage.length < 4300)
        assertTrue(event.formattedMessage.lines().size < 30)
        assertFalse(event.formattedMessage.contains("synthetic-private"))
    }

    @Test
    fun `깊은 원인 사슬과 긴 소스 메타데이터가 있을 때__처리하면__진단 깊이와 길이 한도를 지킨다`() {
        // given
        val last = IOException("synthetic-private-cause")
        val fourth = SQLException("synthetic-private-SQL", last)
        val third = UnsupportedOperationException("synthetic-private-operation", fourth)
        val second = IllegalArgumentException("synthetic-private-input", third)
        val failure = IllegalStateException("synthetic-private-member", second)


        // when
        val deep = handleAndCapture(failure)

        // then
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
