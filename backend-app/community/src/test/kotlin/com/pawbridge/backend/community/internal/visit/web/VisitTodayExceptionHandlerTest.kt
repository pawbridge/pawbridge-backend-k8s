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
    fun given__storage_failure__when__handling__then__return_exact_503_envelope_with_no_store() {
        val response = VisitTodayExceptionHandler().storageUnavailable()

        assertEquals(503, response.statusCode.value())
        assertEquals("no-store", response.headers.cacheControl)
        assertEquals(
            VisitTodayController.Response<Void>(503, "방문 통계를 불러올 수 없습니다.", null),
            response.body,
        )
    }

    @Test
    fun given__runtime_with_private_messages_and_causes__when__handling__then__return_500_without_new_cache_policy_and_log_only_type() {
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
            val response = VisitTodayExceptionHandler().serverError(failure)

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
