package com.pawbridge.backend.community.internal.visit.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException

class VisitTodayExceptionHandlerTest {
    @Test
    fun `저장소 실패일 때__처리하면__캐시 금지가 붙은 정확한 503 응답 봉투를 돌려준다`() {
        // given
        val handler = VisitTodayExceptionHandler()

        // when
        val response = handler.storageUnavailable()

        // then
        assertEquals(503, response.statusCode.value())
        assertEquals("no-store", response.headers.cacheControl)
        assertEquals(
            VisitTodayController.Response<Void>(503, "방문 통계를 불러올 수 없습니다.", null),
            response.body,
        )
    }

    @Test
    fun `비공개 메시지와 원인이 있는 런타임 예외일 때__처리하면__새 캐시 정책 없이 500을 돌려주고 예외 형식만 로그에 남긴다`() {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        failure.addSuppressed(IllegalArgumentException("synthetic-private-user"))
        val logger = LoggerFactory.getLogger(VisitTodayExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = VisitTodayExceptionHandler().serverError(failure)

            // then
            assertEquals(500, response.statusCode.value())
            assertNull(response.headers.cacheControl)
            assertEquals(VisitTodayController.Response<Void>(500, "서버 에러!", null), response.body)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertNull(event.throwableProxy)
            assertEquals("Visit today request failed (IllegalStateException)", event.formattedMessage)
            assertEquals(1, event.argumentArray.size)
            assertEquals("IllegalStateException", event.argumentArray[0])
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
