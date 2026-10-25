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
    fun `승인된 컨트롤러 진입일 때__삭제하면__한 번만 호출하고 원래의 성공 응답 형식을 유지한다`() {
        // given
        val ids = ArrayList<Long>()
        val controller = AdminUserDeletionController(UserDeletion { id -> ids.add(id) })

        // when
        val response = controller.delete(7L as java.lang.Long)

        // then
        assertEquals(listOf(7L), ids)
        assertEquals(200, response.statusCode.value())
        assertEquals(AdminUserDeletionController.Response<Void>(200, "회원이 삭제되었습니다.", null), response.body)
    }

    @Test
    fun `기존 관리자 삭제 진입점만 있을 때__진입점과 어드바이스를 살펴보면__정확한 매핑과 한정된 핸들러를 유지한다`() {
        // given
        val method = AdminUserDeletionController::class.java.getMethod("delete", java.lang.Long::class.java)

        // when
        val mapping = method.getAnnotation(DeleteMapping::class.java)
        val advice = AdminUserDeletionExceptionHandler::class.java.getAnnotation(RestControllerAdvice::class.java)

        // then
        assertEquals("userId", method.parameters[0].getAnnotation(PathVariable::class.java).value)
        assertArrayEquals(arrayOf("/api/v1/admin/users/{userId}"), mapping.value)
        assertArrayEquals(arrayOf<Class<*>>(AdminUserDeletionController::class.java), advice.assignableTypes.map { type -> type.java }.toTypedArray())
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `회원 없음 또는 커뮤니티 정리나 최종 계정 실패일 때__실제 컨트롤러가 핸들러로 전파하면__비공개 상세 없이 원래의 상태 응답 형식을 유지한다`(
        kind: Failure,
    ) {
        // given
        val failure: RuntimeException = when (kind) {
            Failure.MISSING -> UserNotFoundException()
            Failure.COMMUNITY -> MemberCleanupUnavailableException()
            Failure.SQL -> DataAccessResourceFailureException("private-sql-token")
            Failure.COMMIT -> TransactionSystemException("private-commit-body")
        }
        val controller = AdminUserDeletionController(UserDeletion { id -> throw failure })
        val handler = AdminUserDeletionExceptionHandler()
        val logger = LoggerFactory.getLogger(AdminUserDeletionExceptionHandler::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>()

        // when
        assertSame(failure, assertThrows(RuntimeException::class.java) { controller.delete(7L as java.lang.Long) })
        logs.start()
        logger.addAppender(logs)
        try {
            val response = when (kind) {
                Failure.MISSING -> handler.missing(failure as UserNotFoundException)
                Failure.COMMUNITY -> handler.cleanupUnavailable(failure as MemberCleanupUnavailableException)
                Failure.SQL, Failure.COMMIT -> handler.unexpected(failure)
            }

            // then
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
