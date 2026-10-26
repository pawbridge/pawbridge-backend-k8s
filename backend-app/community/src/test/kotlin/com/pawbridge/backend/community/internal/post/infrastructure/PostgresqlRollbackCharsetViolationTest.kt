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
    fun `구조화된 커뮤니티 문자셋 검사 위반일 때__분류하면__중첩 원인과 배치 원인을 모두 일치로 본다`() {
        // given
        val nested = DataIntegrityViolationException(
            "synthetic",
            error("23514", "pawbridge_community", "ck_rollback_charset_posts"),
        )
        val batch = BatchUpdateException()
        batch.setNextException(error("23514", "pawbridge_community", "ck_rollback_charset_comments"))

        // when
        val nestedMatches = PostgresqlRollbackCharsetViolation.matches(nested)
        val batchMatches = PostgresqlRollbackCharsetViolation.matches(batch)

        // then
        assertTrue(nestedMatches)
        assertTrue(batchMatches)
    }

    @Test
    fun `다른 상태나 스키마나 제약이거나 구조화되지 않은 문구일 때__분류하면__서버 오류를 그대로 보존한다`() {
        // given
        val otherState = error("23505", "pawbridge_community", "ck_rollback_charset_posts")
        val otherSchema = error("23514", "other_schema", "ck_rollback_charset_posts")
        val otherConstraint = error("23514", "pawbridge_community", "business_constraint")
        val unstructured = SQLException("ck_rollback_charset_posts", "23514")

        // when
        val otherStateMatches = PostgresqlRollbackCharsetViolation.matches(otherState)
        val otherSchemaMatches = PostgresqlRollbackCharsetViolation.matches(otherSchema)
        val otherConstraintMatches = PostgresqlRollbackCharsetViolation.matches(otherConstraint)
        val unstructuredMatches = PostgresqlRollbackCharsetViolation.matches(unstructured)

        // then
        assertFalse(otherStateMatches)
        assertFalse(otherSchemaMatches)
        assertFalse(otherConstraintMatches)
        assertFalse(unstructuredMatches)
    }

    @Test
    fun `예외 체인이 순환할 때__분류하면__메시지 문구와 맞추지 않고 순회를 끝낸다`() {
        // given
        val first = SQLException("ck_rollback_charset_posts", "23514")
        val second = SQLException("synthetic")
        first.initCause(second)
        second.initCause(first)

        // when
        val matches = PostgresqlRollbackCharsetViolation.matches(first)

        // then
        assertFalse(matches)
    }

    private fun error(state: String, schema: String, constraint: String): PSQLException =
        PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C" + state + "\u0000Msynthetic\u0000s" + schema + "\u0000n" + constraint + "\u0000",
            ),
        )
}
