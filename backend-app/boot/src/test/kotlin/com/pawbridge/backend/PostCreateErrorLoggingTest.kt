package com.pawbridge.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.post.web.ApiResponse
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException

class PostCreateErrorLoggingTest {
    @Test
    fun `민감한 메시지가 든 실패가 주어졌을 때__처리하면__민감 상세 없이 유형과 위치만 기록한다`() {
        // given
        val cause = SQLException("jdbc:postgresql://example.test/db?password=synthetic-db-password")
        cause.stackTrace = arrayOf(
            StackTraceElement(
                "com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter",
                "create",
                "JdbcPostWriter.java",
                73,
            ),
        )
        val failure = IllegalStateException("Bearer synthetic-jwt-secret", cause)
        failure.addSuppressed(RuntimeException("https://example.test/object?signature=synthetic-signed-url"))

        // when
        val event = handleAndCapture(failure)

        // then
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.throwableProxy).isNull()
        assertThat(event.argumentArray).hasSize(1).allSatisfy { argument ->
            assertThat(argument).isInstanceOf(String::class.java)
        }
        assertThat(event.formattedMessage)
            .contains(
                "Unexpected post creation failure",
                "java.lang.IllegalStateException",
                "java.sql.SQLException",
                "JdbcPostWriter.create",
                "73",
            )
            .doesNotContain("synthetic-jwt-secret", "synthetic-db-password", "synthetic-signed-url", "Bearer ", "jdbc:")
    }

    @Test
    @Timeout(2)
    fun `순환 원인과 많은 프레임이 주어졌을 때__처리하면__진단 정보가 제한된다`() {
        // given
        val failure = IllegalStateException("synthetic-private-message")
        val cause = IllegalArgumentException("synthetic-private-cause", failure)
        failure.initCause(cause)
        val frames = Array(100) {
            StackTraceElement(
                "com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter",
                "create",
                "JdbcPostWriter.java",
                73,
            )
        }
        failure.stackTrace = frames
        cause.stackTrace = frames

        // when
        val event = handleAndCapture(failure)

        // then
        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage).contains("IllegalStateException", "IllegalArgumentException")
            .doesNotContain("synthetic-private-message", "synthetic-private-cause")
        assertThat(event.formattedMessage.length).isLessThan(4300)
        assertThat(event.formattedMessage.lines().size).isLessThan(30)
    }

    @Test
    fun `깊은 원인 사슬이 주어졌을 때__처리하면__진단 예산을 넘는 원인을 생략한다`() {
        // given
        val last = IOException("synthetic-private-cause")
        val sql = SQLException("synthetic-private-sql", last)
        val unsupported = UnsupportedOperationException("synthetic-private-operation", sql)
        val invalid = IllegalArgumentException("synthetic-private-input", unsupported)
        val failure = IllegalStateException("synthetic-private-message", invalid)

        // when
        val event = handleAndCapture(failure)

        // then
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
    fun `긴 소스 메타데이터가 주어졌을 때__처리하면__진단 텍스트 길이를 제한한다`() {
        // given
        val failure = IllegalStateException("synthetic-private-message")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "create", "Post.java", 73),
        )

        // when
        val event = handleAndCapture(failure)

        // then
        assertThat(event.throwableProxy).isNull()
        assertThat(event.argumentArray[0] as String).startsWith("java.lang.IllegalStateException")
            .hasSizeLessThanOrEqualTo(4096)
            .doesNotContain("synthetic-private-message")
    }

    companion object {
        private fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
            val logger = LoggerFactory.getLogger(PostCreateExceptionHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val response = PostCreateExceptionHandler().serverError(failure)
                assertThat(response.statusCode.value()).isEqualTo(500)
                assertThat(response.body).isEqualTo(ApiResponse<Void?>(500, "서버 에러!", null))
                assertThat(appender.list).hasSize(1)
                return appender.list.first()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }
}
