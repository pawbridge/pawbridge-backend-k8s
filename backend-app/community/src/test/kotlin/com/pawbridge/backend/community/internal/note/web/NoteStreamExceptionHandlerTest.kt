package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.slf4j.LoggerFactory

class NoteStreamExceptionHandlerTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenPrivateRuntimeAndResponseCommitState__whenHandlingStreamFailure__thenLogTypeOnlyAndNeverWriteJsonAfterCommit(
        committed: Boolean,
    ) {
        val response = mock(HttpServletResponse::class.java)
        whenever(response.isCommitted).thenReturn(committed)
        val failure = IllegalStateException("Bearer synthetic-private", RuntimeException("private SQL/body"))
        failure.addSuppressed(RuntimeException("private credentials"))
        val logger = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val result = NoteStreamExceptionHandler().unexpectedFailure(failure, response)

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
                verify(response, never()).setHeader(anyString(), anyString())
            } else {
                verify(response).setHeader("Cache-Control", "no-store")
            }
            verify(response).isCommitted
            verifyNoMoreInteractions(response)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
