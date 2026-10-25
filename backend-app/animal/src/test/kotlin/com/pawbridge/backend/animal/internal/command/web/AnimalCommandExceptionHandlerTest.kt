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
    fun original_rollback_charset_violation_is_400_through_database_wrapper() {
        val response = handler.database(
            DataIntegrityViolationException("check", error("pawbridge_animal", "ck_rollback_charset_animals", "23514")),
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.message).isEqualTo(PostgresqlRollbackCharsetViolation.MESSAGE)
    }

    @Test
    fun charset_violation_is_400_through_commit_and_batch_next_exception_wrappers() {
        val batch = BatchUpdateException()
        batch.nextException = error("pawbridge_animal", "ck_rollback_charset_shelters", "23514")

        val response = handler.unexpected(RuntimeException("commit", batch))

        assertThat(response.statusCode.value()).isEqualTo(400)
    }

    @ParameterizedTest
    @ValueSource(strings = ["foreign-schema", "foreign-constraint", "foreign-state"])
    fun unrelated_database_error_remains_500(kind: String) {
        val failure = error(
            if (kind == "foreign-schema") "public" else "pawbridge_animal",
            if (kind == "foreign-constraint") "fk_animal_chat" else "ck_rollback_charset_animals",
            if (kind == "foreign-state") "23503" else "23514",
        )

        assertThat(handler.database(DataIntegrityViolationException("db", failure)).statusCode.value()).isEqualTo(500)
        assertThat(handler.unexpected(RuntimeException("commit", failure)).statusCode.value()).isEqualTo(500)
    }

    @Test
    fun original_missing_entity_remains_500_and_new_authorization_denial_is_403() {
        assertThat(handler.unexpected(EntityNotFoundException("missing")).statusCode.value()).isEqualTo(500)
        assertThat(handler.forbidden(AnimalCommandForbiddenException()).statusCode.value()).isEqualTo(403)
    }
}
