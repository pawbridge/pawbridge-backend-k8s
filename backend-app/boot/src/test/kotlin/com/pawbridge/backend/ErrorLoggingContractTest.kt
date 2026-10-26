package com.pawbridge.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.post.web.ApiResponse
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import java.io.IOException
import java.sql.SQLException

/**
 * Each of these exception handlers carries its own copy of the bounded, metadata-only diagnostic trace written for
 * an unexpected failure. The same four limits are checked on every copy: no message text, no credentials, no
 * cycles or runaway length, no causes beyond the budget.
 */
class ErrorLoggingContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    @Timeout(2)
    fun `민감한 메시지와 순환 원인과 깊은 사슬과 긴 출처가 주어졌을 때__처리하면__유형과 위치만 한도 안에서 기록한다`(handler: Handler) {
        // given
        val sensitive = listOf(
            "synthetic-jwt-secret", "synthetic-private-comment", "synthetic-member-email", "synthetic-member-name",
            "synthetic-db-password", "synthetic-signed-url", "synthetic-private-message", "synthetic-private-cause",
        )

        val location = SQLException("jdbc:postgresql://example.test/db?password=synthetic-db-password")
        location.stackTrace = arrayOf(StackTraceElement(handler.sourceClass, handler.sourceMethod, handler.sourceFile, 73))
        val secret = IllegalStateException(
            "Bearer synthetic-jwt-secret; synthetic-private-comment; synthetic-member-email; synthetic-member-name",
            location,
        )
        secret.addSuppressed(RuntimeException("https://example.test/object?signature=synthetic-signed-url"))

        val cyclic = IllegalStateException("synthetic-private-message")
        val cyclicCause = IllegalArgumentException("synthetic-private-cause", cyclic)
        cyclic.initCause(cyclicCause)
        val frames = Array(100) { StackTraceElement(handler.sourceClass, handler.sourceMethod, handler.sourceFile, 73) }
        cyclic.stackTrace = frames
        cyclicCause.stackTrace = frames

        val deep = IllegalStateException(
            "synthetic-private-message",
            IllegalArgumentException(
                "synthetic-private-input",
                UnsupportedOperationException("synthetic-private-operation", SQLException("synthetic-private-sql", IOException("synthetic-private-cause"))),
            ),
        )

        val longSource = IllegalStateException("synthetic-private-message")
        longSource.stackTrace = arrayOf(
            StackTraceElement("com.pawbridge." + "LongSourceLocation".repeat(400), handler.sourceMethod, "Source.java", 73),
        )

        // when
        val secretEvent = handler.handle(secret)
        val cyclicEvent = handler.handle(cyclic)
        val deepEvent = handler.handle(deep)
        val longEvent = handler.handle(longSource)

        // then
        assertThat(secretEvent.level).isEqualTo(Level.ERROR)
        assertThat(secretEvent.throwableProxy).isNull()
        assertThat(secretEvent.argumentArray).hasSize(1).allSatisfy { argument ->
            assertThat(argument).isInstanceOf(String::class.java)
        }
        assertThat(secretEvent.formattedMessage)
            .contains(
                handler.prefix, "java.lang.IllegalStateException", "java.sql.SQLException",
                "${handler.sourceClass.substringAfterLast('.')}.${handler.sourceMethod}", "73",
            )
            .doesNotContain(*sensitive.toTypedArray(), "Bearer ", "jdbc:")

        assertThat(cyclicEvent.throwableProxy).isNull()
        assertThat(cyclicEvent.formattedMessage).contains("IllegalStateException", "IllegalArgumentException")
            .doesNotContain(*sensitive.toTypedArray())
        assertThat(cyclicEvent.formattedMessage.length).isLessThan(4300)
        assertThat(cyclicEvent.formattedMessage.lines().size).isLessThan(30)

        assertThat(deepEvent.throwableProxy).isNull()
        assertThat(deepEvent.formattedMessage)
            .contains("IllegalStateException", "IllegalArgumentException", "UnsupportedOperationException", "java.sql.SQLException")
            .doesNotContain("java.io.IOException", "synthetic-private")

        assertThat(longEvent.throwableProxy).isNull()
        assertThat(longEvent.argumentArray[0] as String).startsWith("java.lang.IllegalStateException")
            .hasSizeLessThanOrEqualTo(4096)
            .doesNotContain("synthetic-private-message")
    }

    class Handler(
        private val name: String,
        private val loggerOwner: Class<*>,
        val prefix: String,
        val sourceClass: String,
        val sourceMethod: String,
        val sourceFile: String,
        private val expectedBody: Any,
        private val serverError: (RuntimeException) -> ResponseEntity<*>,
    ) {
        fun handle(failure: RuntimeException): ILoggingEvent {
            val logger = LoggerFactory.getLogger(loggerOwner) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val response = serverError(failure)
                assertThat(response.statusCode.value()).isEqualTo(500)
                assertThat(response.body).isEqualTo(expectedBody)
                assertThat(appender.list).hasSize(1)
                return appender.list.first()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        override fun toString(): String = name
    }

    companion object {
        private const val POST = "com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter"

        @JvmStatic
        fun handlers(): List<Handler> = listOf(
            Handler(
                "게시글 작성", PostCreateExceptionHandler::class.java, "Unexpected post creation failure",
                POST, "create", "JdbcPostWriter.java", ApiResponse<Void?>(500, "서버 에러!", null),
            ) { PostCreateExceptionHandler().serverError(it) },
            Handler(
                "게시글 수정", PostUpdateExceptionHandler::class.java, "Unexpected post update failure",
                POST, "update", "JdbcPostWriter.java", ApiResponse<Void?>(500, "서버 에러!", null),
            ) { PostUpdateExceptionHandler().serverError(it) },
            Handler(
                "게시글 삭제", PostDeleteExceptionHandler::class.java, "Unexpected post deletion failure",
                "com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostDeletion", "delete",
                "JdbcPostDeletion.java", ApiResponse<Void?>(500, "서버 에러!", null),
            ) { PostDeleteExceptionHandler().serverError(it) },
            Handler(
                "댓글 수정", CommentUpdateExceptionHandler::class.java, "Unexpected comment update failure",
                "com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter", "update",
                "JdbcCommentWriter.java", CommentUpdateController.Response<Void?>(500, "서버 에러!", null),
            ) { CommentUpdateExceptionHandler().serverError(it) },
            Handler(
                "댓글 삭제", CommentDeleteExceptionHandler::class.java, "Unexpected comment deletion failure",
                "com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentDeletion", "delete",
                "JdbcCommentDeletion.java", CommentDeleteController.Response<Void?>(500, "서버 에러!", null),
            ) { CommentDeleteExceptionHandler().serverError(it) },
            Handler(
                "회원 프로필 조회", UserProfileExceptionHandler::class.java, "Unexpected user profile lookup failure",
                "com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery", "require",
                "JdbcUserProfileQuery.java", UserProfileController.Response<Void?>(500, "서버 에러!", null),
            ) { UserProfileExceptionHandler().serverError(it) },
        )
    }
}
