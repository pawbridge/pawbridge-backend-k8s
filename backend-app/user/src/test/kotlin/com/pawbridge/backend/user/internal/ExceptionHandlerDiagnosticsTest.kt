package com.pawbridge.backend.user.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import java.io.IOException
import java.sql.SQLException
import java.util.stream.Stream

/**
 * Handlers that answer an unexpected failure with a generic 500 keep their own bounded diagnostic trace: only
 * exception types and source locations reach the log, never messages, and cycles and long frames are cut off.
 */
class ExceptionHandlerDiagnosticsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    fun `민감한 실패 메시지와 억제된 예외 문구가 있을 때__처리하면__타입과 소스 위치만 로그에 남긴다`(handler: Handler) {
        // given
        val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
        cause.stackTrace = arrayOf(handler.frame())
        val failure = IllegalStateException("Bearer synthetic-private-token synthetic-private-member", cause)
        failure.addSuppressed(RuntimeException("https://fixture.invalid/object?signature=synthetic-private-signature"))

        // when
        val event = handler.handleAndCapture(failure)

        // then
        assertNull(event.throwableProxy)
        assertEquals(1, event.argumentArray.size)
        assertInstanceOf(String::class.java, event.argumentArray[0])
        val message = event.formattedMessage
        assertTrue(message.contains("java.lang.IllegalStateException"))
        assertTrue(message.contains("java.sql.SQLException"))
        assertTrue(message.contains("${handler.sourceClass.substringAfterLast('.')}.${handler.sourceMethod}:73"))
        assertFalse(message.contains("synthetic-private"))
        assertFalse(message.contains("Bearer "))
        assertFalse(message.contains("jdbc:"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    @Timeout(2)
    fun `순환 원인과 많은 프레임이 있을 때__처리하면__진단이 제한된 출력으로 끝난다`(handler: Handler) {
        // given
        val failure = IllegalStateException("synthetic-private-member")
        val cause = SQLException("synthetic-private-cause", "23514")
        failure.initCause(cause)
        cause.initCause(failure)
        val frames = Array(100) { handler.frame() }
        failure.stackTrace = frames
        cause.stackTrace = frames

        // when
        val event = handler.handleAndCapture(failure)

        // then
        assertNull(event.throwableProxy)
        assertTrue(event.formattedMessage.length < 4300)
        assertTrue(event.formattedMessage.lines().size < 30)
        assertFalse(event.formattedMessage.contains("synthetic-private"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    fun `깊은 원인 사슬과 긴 소스 메타데이터가 있을 때__처리하면__깊이와 길이 한도를 지킨다`(handler: Handler) {
        // given
        val last = IOException("synthetic-private-cause")
        val fourth = SQLException("synthetic-private-SQL", last)
        val third = UnsupportedOperationException("synthetic-private-operation", fourth)
        val second = IllegalArgumentException("synthetic-private-input", third)
        val failure = IllegalStateException("synthetic-private-member", second)

        // when
        val deep = handler.handleAndCapture(failure)

        // then
        assertTrue(deep.formattedMessage.contains("java.sql.SQLException"))
        assertFalse(deep.formattedMessage.contains("java.io.IOException"))
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), handler.sourceMethod, "User.java", 73),
        )
        val longMetadata = handler.handleAndCapture(failure)
        assertTrue((longMetadata.argumentArray[0] as String).length <= 4096)
        assertFalse(longMetadata.formattedMessage.contains("synthetic-private"))
    }

    class Handler(
        private val name: String,
        private val loggerType: Class<*>,
        val sourceClass: String,
        val sourceMethod: String,
        private val expectedBody: Any,
        private val handle: (RuntimeException) -> ResponseEntity<*>,
    ) {
        override fun toString(): String = name

        fun frame() = StackTraceElement(sourceClass, sourceMethod, sourceClass.substringAfterLast('.') + ".java", 73)

        fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
            val logger = LoggerFactory.getLogger(loggerType) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val response = handle(failure)
                assertEquals(500, response.statusCode.value())
                assertEquals(expectedBody, response.body)
                assertEquals(1, appender.list.size)
                return appender.list.first()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }

    companion object {
        @JvmStatic
        fun handlers(): Stream<Arguments> = Stream.of(
            Handler(
                name = "즐겨찾기 상태",
                loggerType = FavoriteStatusExceptionHandler::class.java,
                sourceClass = "com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery",
                sourceMethod = "isFavorite",
                expectedBody = FavoriteStatusController.Response<Void>(500, "서버 에러!", null),
                handle = { FavoriteStatusExceptionHandler().serverError(it) },
            ),
            Handler(
                name = "닉네임 변경",
                loggerType = UserNicknameExceptionHandler::class.java,
                sourceClass = "com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore",
                sourceMethod = "updateNickname",
                expectedBody = UserNicknameController.Response<Void>(500, "서버 에러!", null),
                handle = { UserNicknameExceptionHandler().serverError(it) },
            ),
        ).map { Arguments.of(it) }
    }
}
