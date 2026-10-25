@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.user.internal.deletion.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Direct controller/handler unit contracts, not MVC binding or ROLE_ADMIN/filter-chain evidence. */
class AdminUserDeletionControllerTest {
    @Test
    fun givenAuthorizedControllerEntry__whenDeleting__thenInvokeOnceAndKeepTheOriginalSuccessEnvelope() {
        val ids = ArrayList<Long>()
        val response = AdminUserDeletionController(UserDeletion { id -> ids.add(id) }).delete(7L as java.lang.Long)

        assertEquals(listOf(7L), ids)
        assertEquals(200, response.statusCode.value())
        assertEquals(AdminUserDeletionController.Response<Void>(200, "회원이 삭제되었습니다.", null), response.body)
    }

    @Test
    fun givenOnlyTheExistingAdminDelete__whenInspectingEntryAndAdvice__thenKeepTheExactMappingAndScopedHandler() {
        val method = AdminUserDeletionController::class.java.getMethod("delete", java.lang.Long::class.java)
        val mapping = method.getAnnotation(DeleteMapping::class.java)
        assertEquals("userId", method.parameters[0].getAnnotation(PathVariable::class.java).value)
        assertArrayEquals(arrayOf("/api/v1/admin/users/{userId}"), mapping.value)
        val advice = AdminUserDeletionExceptionHandler::class.java.getAnnotation(RestControllerAdvice::class.java)
        assertArrayEquals(arrayOf<Class<*>>(AdminUserDeletionController::class.java), advice.assignableTypes.map { type -> type.java }.toTypedArray())
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun givenMissingCommunityOrFinalAccountFailure__whenTheActualControllerPropagatesToItsHandler__thenKeepOriginalStatusEnvelopeWithoutPrivateDetails(
        kind: Failure,
    ) {
        val failure: RuntimeException = when (kind) {
            Failure.MISSING -> UserNotFoundException()
            Failure.COMMUNITY -> MemberCleanupUnavailableException()
            Failure.SQL -> DataAccessResourceFailureException("private-sql-token")
            Failure.COMMIT -> TransactionSystemException("private-commit-body")
        }
        val controller = AdminUserDeletionController(UserDeletion { id -> throw failure })
        assertSame(failure, assertThrows(RuntimeException::class.java) { controller.delete(7L as java.lang.Long) })
        val handler = AdminUserDeletionExceptionHandler()
        val logger = LoggerFactory.getLogger(AdminUserDeletionExceptionHandler::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>()
        logs.start()
        logger.addAppender(logs)
        try {
            val response = when (kind) {
                Failure.MISSING -> handler.missing(failure as UserNotFoundException)
                Failure.COMMUNITY -> handler.cleanupUnavailable(failure as MemberCleanupUnavailableException)
                Failure.SQL, Failure.COMMIT -> handler.unexpected(failure)
            }
            val status = if (kind == Failure.MISSING) 400 else if (kind == Failure.COMMUNITY) 503 else 500
            val message = if (kind == Failure.MISSING) {
                "존재하지 않는 회원입니다."
            } else if (kind == Failure.COMMUNITY) {
                "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요."
            } else {
                "서버 에러!"
            }
            assertEquals(status, response.statusCode.value())
            assertEquals(AdminUserDeletionController.Response<Void>(status, message, null), response.body)
            for (event in logs.list) {
                assertNull(event.throwableProxy)
                assertFalse(event.formattedMessage.contains("private-"))
            }
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    enum class Failure { MISSING, COMMUNITY, SQL, COMMIT }
}
