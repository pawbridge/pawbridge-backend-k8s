package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

class PostgresqlRollbackCharsetViolationTest {
    @Test
    void given__structured_community_charset_check__when__classify__then__nested_and_batch_causes_match() {
        assertTrue(PostgresqlRollbackCharsetViolation.matches(new DataIntegrityViolationException("synthetic",
                error("23514", "pawbridge_community", "ck_rollback_charset_posts"))));
        BatchUpdateException batch = new BatchUpdateException();
        batch.setNextException(error("23514", "pawbridge_community", "ck_rollback_charset_outbox_events"));
        assertTrue(PostgresqlRollbackCharsetViolation.matches(batch));
    }

    @Test
    void given__other_state_schema_constraint_or_unstructured_text__when__classify__then__server_error_is_preserved() {
        assertFalse(PostgresqlRollbackCharsetViolation.matches(error("23505", "pawbridge_community", "ck_rollback_charset_posts")));
        assertFalse(PostgresqlRollbackCharsetViolation.matches(error("23514", "other_schema", "ck_rollback_charset_posts")));
        assertFalse(PostgresqlRollbackCharsetViolation.matches(error("23514", "pawbridge_community", "business_constraint")));
        assertFalse(PostgresqlRollbackCharsetViolation.matches(new SQLException("ck_rollback_charset_posts", "23514")));
    }

    @Test
    void given__cyclic_exception_chain__when__classify__then__traversal_finishes_without_matching_message_text() {
        SQLException first = new SQLException("ck_rollback_charset_posts", "23514");
        SQLException second = new SQLException("synthetic");
        first.initCause(second);
        second.initCause(first);
        assertFalse(PostgresqlRollbackCharsetViolation.matches(first));
    }

    private PSQLException error(String state, String schema, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0Msynthetic\0s" + schema + "\0n" + constraint + "\0"));
    }
}
