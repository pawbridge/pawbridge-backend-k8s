package com.pawbridge.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException

class UserProfileErrorLoggingTest {
    @Test
    fun `민감한 회원·토큰 문구가 담긴 실패가 주어졌을 때__처리하면__타입과 위치만 로그에 남긴다`() {
        // given
        val cause = SQLException("jdbc:postgresql://example.test/db?password=synthetic-db-password")
        cause.stackTrace = arrayOf(
            StackTraceElement(
                "com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery",
                "require",
                "JdbcUserProfileQuery.java",
                73,
            ),
        )
        val failure = IllegalStateException("Bearer synthetic-jwt-secret; synthetic-member-email; synthetic-member-name", cause)
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
            .contains("java.lang.IllegalStateException", "java.sql.SQLException", "JdbcUserProfileQuery.require", "73")
            .doesNotContain(
                "synthetic-jwt-secret",
                "synthetic-member-email",
                "synthetic-member-name",
                "synthetic-db-password",
                "synthetic-signed-url",
                "Bearer ",
                "jdbc:",
            )
    }

    @Test
    @Timeout(2)
    fun `순환 원인과 많은 프레임이 주어졌을 때__처리하면__진단이 제한된 크기에 머문다`() {
        // given
        val failure = IllegalStateException("synthetic-private-member")
        val cause = IllegalArgumentException("synthetic-private-cause", failure)
        failure.initCause(cause)
        val frames = Array(100) {
            StackTraceElement(
                "com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery",
                "require",
                "JdbcUserProfileQuery.java",
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
            .doesNotContain("synthetic-private-member", "synthetic-private-cause")
        assertThat(event.formattedMessage.length).isLessThan(4300)
        assertThat(event.formattedMessage.lines().size).isLessThan(30)
    }

    @Test
    fun `깊은 원인 체인이 주어졌을 때__처리하면__진단 예산을 넘는 원인은 생략한다`() {
        // given
        val last = IOException("synthetic-private-cause")
        val sql = SQLException("synthetic-private-sql", last)
        val unsupported = UnsupportedOperationException("synthetic-private-operation", sql)
        val invalid = IllegalArgumentException("synthetic-private-input", unsupported)
        val failure = IllegalStateException("synthetic-private-member", invalid)

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
    fun `긴 소스 메타데이터가 주어졌을 때__처리하면__진단 문구 길이를 제한한다`() {
        // given
        val failure = IllegalStateException("synthetic-private-member")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), "require", "User.java", 73),
        )

        // when
        val event = handleAndCapture(failure)

        // then
        assertThat(event.throwableProxy).isNull()
        assertThat(event.argumentArray[0] as String).startsWith("java.lang.IllegalStateException")
            .hasSizeLessThanOrEqualTo(4096)
            .doesNotContain("synthetic-private-member")
    }

    companion object {
        private fun handleAndCapture(failure: RuntimeException): ILoggingEvent {
            val logger = LoggerFactory.getLogger(UserProfileExceptionHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val response = UserProfileExceptionHandler().serverError(failure)
                assertThat(response.statusCode.value()).isEqualTo(500)
                assertThat(response.body).isEqualTo(UserProfileController.Response<Void?>(500, "서버 에러!", null))
                assertThat(appender.list).hasSize(1)
                return appender.list.first()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }
}
