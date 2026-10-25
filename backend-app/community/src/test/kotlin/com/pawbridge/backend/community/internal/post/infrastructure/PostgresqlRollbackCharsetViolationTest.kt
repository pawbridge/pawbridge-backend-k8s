package com.pawbridge.backend.community.internal.post.infrastructure

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import java.sql.BatchUpdateException
import java.sql.SQLException

class PostgresqlRollbackCharsetViolationTest {
    @Test
    fun given__structured_community_charset_check__when__classify__then__nested_and_batch_causes_match() {
        assertTrue(
            PostgresqlRollbackCharsetViolation.matches(
                DataIntegrityViolationException(
                    "synthetic",
                    error("23514", "pawbridge_community", "ck_rollback_charset_posts"),
                ),
            ),
        )
        val batch = BatchUpdateException()
        batch.setNextException(error("23514", "pawbridge_community", "ck_rollback_charset_comments"))
        assertTrue(PostgresqlRollbackCharsetViolation.matches(batch))
    }

    @Test
    fun given__other_state_schema_constraint_or_unstructured_text__when__classify__then__server_error_is_preserved() {
        assertFalse(
            PostgresqlRollbackCharsetViolation.matches(
                error("23505", "pawbridge_community", "ck_rollback_charset_posts"),
            ),
        )
        assertFalse(
            PostgresqlRollbackCharsetViolation.matches(
                error("23514", "other_schema", "ck_rollback_charset_posts"),
            ),
        )
        assertFalse(
            PostgresqlRollbackCharsetViolation.matches(
                error("23514", "pawbridge_community", "business_constraint"),
            ),
        )
        assertFalse(
            PostgresqlRollbackCharsetViolation.matches(SQLException("ck_rollback_charset_posts", "23514")),
        )
    }

    @Test
    fun given__cyclic_exception_chain__when__classify__then__traversal_finishes_without_matching_message_text() {
        val first = SQLException("ck_rollback_charset_posts", "23514")
        val second = SQLException("synthetic")
        first.initCause(second)
        second.initCause(first)
        assertFalse(PostgresqlRollbackCharsetViolation.matches(first))
    }

    private fun error(state: String, schema: String, constraint: String): PSQLException =
        PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C" + state + "\u0000Msynthetic\u0000s" + schema + "\u0000n" + constraint + "\u0000",
            ),
        )
}
