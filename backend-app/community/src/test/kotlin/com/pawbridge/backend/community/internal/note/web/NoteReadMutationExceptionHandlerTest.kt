package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException

class NoteReadMutationExceptionHandlerTest {
    @Test
    fun `비공개 원인과 억제 예외 메시지가 있을 때__처리하면__캐시 금지 일반 503을 돌려주고 예외 형식만 로그에 남긴다`() {
        // given
        val failure = IllegalStateException("Bearer synthetic-private-token", SQLException("synthetic-private-SQL"))
        failure.addSuppressed(IllegalArgumentException("synthetic-private-member-and-note-body"))
        val logger = LoggerFactory.getLogger(NoteReadMutationExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = NoteReadMutationExceptionHandler().unexpectedFailure(failure)

            // then
            assertEquals(503, response.statusCode.value())
            assertEquals("no-store", response.headers.cacheControl)
            assertNull(response.headers.getFirst("Set-Cookie"))
            assertEquals(mapOf("message" to "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), response.body)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertEquals("Private note request failed (IllegalStateException)", event.formattedMessage)
            assertNull(event.throwableProxy)
            assertEquals(1, event.argumentArray.size)
            assertEquals("IllegalStateException", event.argumentArray[0])
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
