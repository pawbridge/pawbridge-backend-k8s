package com.pawbridge.backend.animal.internal.command.web

import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandForbiddenException
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import java.sql.BatchUpdateException

class AnimalCommandExceptionHandlerTest {
    private val handler = AnimalCommandExceptionHandler()

    private fun error(schema: String, constraint: String, state: String): PSQLException {
        return PSQLException(
            ServerErrorMessage("SERROR\u0000C" + state + "\u0000Mconstraint violation\u0000s" + schema + "\u0000n" + constraint + "\u0000\u0000"),
        )
    }

    @Test
    fun `롤백 문자셋 제약 위반이 DB 예외로 감싸졌을 때__DB 예외를 처리하면__400과 원래 메시지를 돌려준다`() {
        // given
        val failure = DataIntegrityViolationException(
            "check",
            error("pawbridge_animal", "ck_rollback_charset_animals", "23514"),
        )

        // when
        val response = handler.database(failure)

        // then
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.message).isEqualTo(PostgresqlRollbackCharsetViolation.MESSAGE)
    }

    @Test
    fun `문자셋 제약 위반이 커밋 예외와 배치 다음 예외로 감싸졌을 때__예상 밖 예외를 처리하면__400을 돌려준다`() {
        // given
        val batch = BatchUpdateException()
        batch.nextException = error("pawbridge_animal", "ck_rollback_charset_shelters", "23514")

        // when
        val response = handler.unexpected(RuntimeException("commit", batch))

        // then
        assertThat(response.statusCode.value()).isEqualTo(400)
    }

    @ParameterizedTest
    @ValueSource(strings = ["foreign-schema", "foreign-constraint", "foreign-state"])
    fun `스키마나 제약이나 상태가 관련 없는 DB 오류일 때__DB 예외와 예상 밖 예외를 처리하면__500을 유지한다`(kind: String) {
        // given
        val failure = error(
            if (kind == "foreign-schema") "public" else "pawbridge_animal",
            if (kind == "foreign-constraint") "fk_animal_chat" else "ck_rollback_charset_animals",
            if (kind == "foreign-state") "23503" else "23514",
        )

        // when
        val databaseResponse = handler.database(DataIntegrityViolationException("db", failure))
        val unexpectedResponse = handler.unexpected(RuntimeException("commit", failure))

        // then
        assertThat(databaseResponse.statusCode.value()).isEqualTo(500)
        assertThat(unexpectedResponse.statusCode.value()).isEqualTo(500)
    }

    @Test
    fun `엔티티가 없거나 권한이 거부됐을 때__예외를 처리하면__없는 엔티티는 500이고 권한 거부는 403이다`() {
        // given
        val missing = EntityNotFoundException("missing")
        val denied = AnimalCommandForbiddenException()

        // when
        val missingResponse = handler.unexpected(missing)
        val deniedResponse = handler.forbidden(denied)

        // then
        assertThat(missingResponse.statusCode.value()).isEqualTo(500)
        assertThat(deniedResponse.statusCode.value()).isEqualTo(403)
    }
}
