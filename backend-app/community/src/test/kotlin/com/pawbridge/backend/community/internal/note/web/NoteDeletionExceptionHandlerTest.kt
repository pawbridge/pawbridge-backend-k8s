package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.sql.SQLException

class NoteDeletionExceptionHandlerTest {
    @Test
    fun given__private_runtime_cause_and_suppressed_messages__when__handling__then__return_no_store_generic_503_and_log_type_without_throwable() {
        val failure = IllegalStateException("Bearer synthetic-private-token", SQLException("synthetic-private-SQL"))
        failure.addSuppressed(IllegalArgumentException("synthetic-private-member-and-note-body"))
        val logger = LoggerFactory.getLogger(NoteDeletionExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val response = NoteDeletionExceptionHandler().unexpectedFailure(failure)

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

    @ParameterizedTest
    @ValueSource(strings = ["MEMBER", "STORAGE"])
    fun given__typed_member_or_storage_handler__when__responding__then__keep_fixed_503_no_store_without_any_log(
        source: String,
    ) {
        val logger = LoggerFactory.getLogger(NoteDeletionExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val handler = NoteDeletionExceptionHandler()
            val response = if (source == "MEMBER") handler.memberUnavailable() else handler.storageUnavailable()

            assertEquals(503, response.statusCode.value())
            assertEquals("no-store", response.headers.cacheControl)
            assertNull(response.headers.getFirst("Set-Cookie"))
            assertEquals(
                mapOf(
                    "message" to if (source == "MEMBER") {
                        "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                    } else {
                        "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
                    },
                ),
                response.body,
            )
            assertEquals(0, appender.list.size)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
