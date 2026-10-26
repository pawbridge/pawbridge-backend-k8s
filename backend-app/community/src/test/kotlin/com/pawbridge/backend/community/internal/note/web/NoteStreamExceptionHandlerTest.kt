package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletResponse

class NoteStreamExceptionHandlerTest {
    @Test
    fun `이미 쓰기 시작한 스트림일 때__시간 초과로 끝나면__응답을 덧쓰지 않고 로그도 남기지 않는다`() {
        // given
        val response = MockHttpServletResponse()
        response.flushBuffer()

        // when
        val events = captureLogs {
            assertNull(NoteStreamExceptionHandler().streamTimedOut(response))
        }

        // then
        assertTrue(events.isEmpty())
    }

    @Test
    fun `쓰기 전인 스트림일 때__시간 초과로 끝나면__기존과 같은 503을 돌려주고 로그는 남기지 않는다`() {
        // given
        val response = MockHttpServletResponse()

        // when
        val events = captureLogs {
            val result = NoteStreamExceptionHandler().streamTimedOut(response)

            // then
            assertEquals(503, result!!.statusCode.value())
            assertEquals(mapOf("message" to "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), result.body)
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
        assertTrue(events.isEmpty())
    }

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }
}
