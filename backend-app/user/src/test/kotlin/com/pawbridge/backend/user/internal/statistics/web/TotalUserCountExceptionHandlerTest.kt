package com.pawbridge.backend.user.internal.statistics.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException

class TotalUserCountExceptionHandlerTest {
    @Test
    fun `비공개 원인과 억제된 메시지가 있는 런타임 예외일 때__처리하면__캐시 지정 없는 공통 500을 돌려주고 예외 유형만 기록한다`() {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        failure.addSuppressed(IllegalArgumentException("synthetic-private-member"))
        val logger = LoggerFactory.getLogger(TotalUserCountExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = TotalUserCountExceptionHandler().serverError(failure)

            // then
            assertEquals(500, response.statusCode.value())
            assertEquals(TotalUserCountController.Response<Void>(500, "서버 에러!", null), response.body)
            assertNull(response.headers.cacheControl)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertEquals("User statistics query failed (IllegalStateException)", event.formattedMessage)
            assertNull(event.throwableProxy)
            assertEquals(1, event.argumentArray.size)
            assertEquals("IllegalStateException", event.argumentArray[0])
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
