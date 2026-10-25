package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory

class NoteStreamExceptionHandlerTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `비공개 런타임 예외와 응답 확정 여부가 주어졌을 때__스트림 실패를 처리하면__예외 형식만 로그에 남기고 확정 뒤에는 JSON을 쓰지 않는다`(
        committed: Boolean,
    ) {
        // given
        val response = mockk<HttpServletResponse>()
        val failure = IllegalStateException("Bearer synthetic-private", RuntimeException("private SQL/body"))
        failure.addSuppressed(RuntimeException("private credentials"))
        val logger = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { response.isCommitted } returns committed
        justRun { response.setHeader(any(), any()) }

        try {
            // when
            val result = NoteStreamExceptionHandler().unexpectedFailure(failure, response)

            // then
            if (committed) {
                assertThat(result).isNull()
            } else {
                assertThat(result!!.statusCode.value()).isEqualTo(503)
                assertThat(result.headers.cacheControl).isEqualTo("no-store")
                assertThat(result.body).isEqualTo(mapOf("message" to "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."))
            }
            assertThat(appender.list).hasSize(1)
            assertThat(appender.list.first().formattedMessage)
                .isEqualTo("Private note request failed (IllegalStateException)")
            assertThat(appender.list.first().throwableProxy).isNull()
            assertThat(appender.list.first().argumentArray).containsExactly("IllegalStateException")
            if (committed) {
                verify(exactly = 0) { response.setHeader(any(), any()) }
            } else {
                verify(exactly = 1) { response.setHeader("Cache-Control", "no-store") }
            }
            verify(exactly = 1) { response.isCommitted }
            confirmVerified(response)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
