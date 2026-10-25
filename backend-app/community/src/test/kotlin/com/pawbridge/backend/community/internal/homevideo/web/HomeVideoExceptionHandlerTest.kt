package com.pawbridge.backend.community.internal.homevideo.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException

class HomeVideoExceptionHandlerTest {
    @Test
    fun `비공개 메시지 원인 억제 데이터가 있는 실패일 때__처리하면__안전한 503을 돌려주고 예외 형식만 로그에 남긴다`() {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        failure.addSuppressed(IllegalArgumentException("https://fixture.invalid/video?key=synthetic-private-key"))
        val logger = LoggerFactory.getLogger(HomeVideoExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = HomeVideoExceptionHandler().failure(failure)

            // then
            assertEquals(503, response.statusCode.value())
            assertEquals(
                HomeVideoController.Response<Void>(503, "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", null),
                response.body,
            )
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertNull(event.throwableProxy)
            assertEquals("Video curation request failed (IllegalStateException)", event.formattedMessage)
            assertEquals(1, event.argumentArray.size)
            assertEquals("IllegalStateException", event.argumentArray[0])
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
