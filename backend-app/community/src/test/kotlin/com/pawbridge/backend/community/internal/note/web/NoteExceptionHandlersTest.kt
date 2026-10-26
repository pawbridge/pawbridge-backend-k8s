package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import java.sql.SQLException
import java.util.stream.Stream

/**
 * Every private-note exception handler answers an unexpected failure with the same cookie-free, no-store,
 * message-only 503 and logs only the exception type. The table lists each handler once.
 */
class NoteExceptionHandlersTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    fun `비공개 원인과 억제 예외 메시지가 있을 때__처리하면__쿠키 없는 캐시 금지 메시지 전용 503을 돌려주고 예외 형식만 로그에 남긴다`(
        handlerType: Class<*>,
        handle: (RuntimeException) -> ResponseEntity<Map<String, String>>,
    ) {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        failure.addSuppressed(IllegalArgumentException("synthetic-private-member-and-note-body"))
        val logger = LoggerFactory.getLogger(handlerType) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = handle(failure)

            // then
            assertEquals(503, response.statusCode.value())
            assertEquals("no-store", response.headers.cacheControl)
            assertNull(response.headers.getFirst("Set-Cookie"))
            assertEquals(mapOf("message" to "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), response.body)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertEquals("Private note request failed (IllegalStateException)", event.formattedMessage)
            assertNull(event.throwableProxy)
            assertEquals(listOf<Any?>("IllegalStateException"), event.argumentArray.toList())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("unavailableHandlers")
    fun `회원 또는 저장소 형식의 실패일 때__응답하면__구분되는 고정 캐시 금지 503을 돌려주고 로그는 남기지 않는다`(
        handlerType: Class<*>,
        handle: () -> ResponseEntity<Map<String, String>>,
        source: String,
        expectedMessage: String,
    ) {
        // given
        val logger = LoggerFactory.getLogger(handlerType) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val response = handle()

            // then
            assertEquals(503, response.statusCode.value())
            assertEquals("no-store", response.headers.cacheControl)
            assertNull(response.headers.getFirst("Set-Cookie"))
            assertEquals(mapOf("message" to expectedMessage), response.body)
            assertEquals(0, appender.list.size)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    companion object {
        private const val MEMBER_MESSAGE = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private const val STORAGE_MESSAGE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."

        private fun unavailable(
            handlerType: Class<*>,
            member: () -> ResponseEntity<Map<String, String>>,
            storage: () -> ResponseEntity<Map<String, String>>,
        ): Stream<Arguments> = Stream.of(
            Arguments.of(handlerType, member, "MEMBER", MEMBER_MESSAGE),
            Arguments.of(handlerType, storage, "STORAGE", STORAGE_MESSAGE),
        )

        @JvmStatic
        fun unavailableHandlers(): Stream<Arguments> {
            val deletion = NoteDeletionExceptionHandler()
            val favorite = NoteFavoriteExceptionHandler()
            val send = NoteSendExceptionHandler()
            return Stream.of(
                unavailable(NoteDeletionExceptionHandler::class.java, deletion::memberUnavailable, deletion::storageUnavailable),
                unavailable(NoteFavoriteExceptionHandler::class.java, favorite::memberUnavailable, favorite::storageUnavailable),
                unavailable(NoteSendExceptionHandler::class.java, send::memberUnavailable, send::storageUnavailable),
            ).flatMap { it }
        }

        private fun row(
            handlerType: Class<*>,
            handle: (RuntimeException) -> ResponseEntity<Map<String, String>>,
        ): Arguments = Arguments.of(
            handlerType,
            handle,
        )

        @JvmStatic
        fun handlers(): Stream<Arguments> = Stream.of(
            row(NoteBlockCreateExceptionHandler::class.java) { NoteBlockCreateExceptionHandler().unexpectedFailure(it) },
            row(NoteBlockListExceptionHandler::class.java) { NoteBlockListExceptionHandler().unexpectedFailure(it) },
            row(NoteBlockRemoveExceptionHandler::class.java) { NoteBlockRemoveExceptionHandler().unexpectedFailure(it) },
            row(NoteBlockStatusExceptionHandler::class.java) { NoteBlockStatusExceptionHandler().unexpectedFailure(it) },
            row(NoteDeletionExceptionHandler::class.java) { NoteDeletionExceptionHandler().unexpectedFailure(it) },
            row(NoteFavoriteExceptionHandler::class.java) { NoteFavoriteExceptionHandler().unexpectedFailure(it) },
            row(NoteNotificationsExceptionHandler::class.java) { NoteNotificationsExceptionHandler().unexpectedFailure(it) },
            row(NoteReadMutationExceptionHandler::class.java) { NoteReadMutationExceptionHandler().unexpectedFailure(it) },
            row(NoteRecipientExceptionHandler::class.java) { NoteRecipientExceptionHandler().unexpectedFailure(it) },
            row(NoteSendExceptionHandler::class.java) { NoteSendExceptionHandler().unexpectedFailure(it) },
            row(PrivateNoteListExceptionHandler::class.java) { PrivateNoteListExceptionHandler().unexpectedFailure(it) },
            row(PrivateNoteReadExceptionHandler::class.java) { PrivateNoteReadExceptionHandler().unexpectedFailure(it) },
        )
    }
}
