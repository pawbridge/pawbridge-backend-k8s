package com.pawbridge.backend.community.internal.post.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class AdminPostStatisticsExceptionHandlerTest {
    @Test
    fun `내부 실행 정보가 담긴 예외일 때__처리하면__기존 500 응답을 돌려주고 예외 유형만 기록하며 캐시 정책을 새로 두지 않는다`() {
        // given
        val logger = LoggerFactory.getLogger(AdminPostStatisticsExceptionHandler::class.java) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)
        try {
            // when
            val response = AdminPostStatisticsExceptionHandler().serverError(
                IllegalStateException("private-body-sql-token", RuntimeException("private-cause")),
            )

            // then
            assertEquals(500, response.statusCode.value())
            assertEquals(ApiResponse<Void>(500, "서버 에러!", null), response.body)
            assertNull(response.headers.cacheControl)
            assertEquals(1, events.list.size)
            assertEquals(
                "Administrator post statistics failed (IllegalStateException)",
                events.list.first().formattedMessage,
            )
            assertNull(events.list.first().throwableProxy)
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }
}
