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
    fun given__private_runtime_cause_and_suppressed_messages__when__handling__then__return_common_500_without_custom_cache_and_log_only_type() {
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
            val response = TotalUserCountExceptionHandler().serverError(failure)

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
