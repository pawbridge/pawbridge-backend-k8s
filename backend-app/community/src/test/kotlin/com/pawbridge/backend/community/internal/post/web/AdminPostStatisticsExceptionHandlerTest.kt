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
    fun given__private_runtime_details__when__handling__then__return_original_500_and_log_only_type_without_new_cache_policy() {
        val logger = LoggerFactory.getLogger(AdminPostStatisticsExceptionHandler::class.java) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)
        try {
            val response = AdminPostStatisticsExceptionHandler().serverError(
                IllegalStateException("private-body-sql-token", RuntimeException("private-cause")),
            )
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
