package com.pawbridge.backend.user.internal.nickname.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionSystemException
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.util.stream.Stream

class UserNicknameExceptionHandlerTest {
    private val handler = UserNicknameExceptionHandler()

    @Test
    fun `구조화된 사용자 문자셋 실패가 커밋에서 감싸졌을 때__처리하면__고정된 문자셋 400 응답을 돌려준다`() {
        // given
        val failure = TransactionSystemException(
            "synthetic private commit text",
            error("23514", "pawbridge_user", "ck_rollback_charset_users"),
        )

        // when
        val response = handler.serverError(failure)

        // then
        assertEquals(400, response.statusCode.value())
        assertEquals(
            UserNicknameController.Response<Void>(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", null),
            response.body,
        )
    }

    @Test
    fun `사용자 문자셋 메타데이터가 JDBC 다음 예외에 있을 때__처리하면__중첩된 배치 메타데이터를 인식한다`() {
        // given
        val batch = BatchUpdateException("synthetic private batch text", "23514", IntArray(0))
        batch.nextException = error("23514", "pawbridge_user", "ck_rollback_charset_users")

        // when
        val response = handler.serverError(DataIntegrityViolationException("synthetic private SQL", batch))

        // then
        assertEquals(400, response.statusCode.value())
    }

    @ParameterizedTest
    @MethodSource("nonCharsetFailures")
    fun `문자셋이 아닌 다른 데이터베이스 실패나 구조 없는 실패일 때__처리하면__안전한 서버 오류를 유지한다`(
        failure: RuntimeException,
    ) {
        // given
        // when
        val response = handler.serverError(failure)

        // then
        assertEquals(500, response.statusCode.value())
        assertEquals(UserNicknameController.Response<Void>(500, "서버 에러!", null), response.body)
    }

    companion object {
        @JvmStatic
        fun nonCharsetFailures(): Stream<RuntimeException> = Stream.of(
            DataIntegrityViolationException("synthetic", error("23505", "pawbridge_user", "ck_rollback_charset_users")),
            DataIntegrityViolationException("synthetic", error("23514", "pawbridge_community", "ck_rollback_charset_users")),
            DataIntegrityViolationException("synthetic", error("23514", "pawbridge_user", "business_constraint")),
            DataIntegrityViolationException(
                "23514 pawbridge_user ck_rollback_charset_users",
                SQLException("23514 pawbridge_user ck_rollback_charset_users", "23514"),
            ),
            TransactionSystemException("synthetic private commit failure"),
        )

        private fun error(state: String, schema: String, constraint: String): PSQLException {
            return PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C" + state + "\u0000Msynthetic-private\u0000s" + schema + "\u0000n" + constraint + "\u0000",
                ),
            )
        }
    }
}
