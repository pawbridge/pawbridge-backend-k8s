package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteBlockReaderTest {
    @Test
    void given__member_pair__when__reading__then__bind_pair_in_both_directions_inside_supplied_read_only_transaction() {
        var fixture = new Fixture();

        assertTrue(fixture.reader.blocked(7L, 99L));

        assertArrayEquals(new Object[] {7L, 99L, 99L, 7L}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__block_query_or_transaction_failure_then_recovery__when__reading__then__propagate_same_failure_without_user_wrapping_or_false_fallback(
            FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case QUERY_DAO -> new DataAccessResourceFailureException("synthetic-private-block-sql");
            case QUERY_RUNTIME -> new IllegalStateException("synthetic-private-block-runtime");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY_DAO, QUERY_RUNTIME -> fixture.jdbc.failure = failure;
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.reader.blocked(7L, 99L)));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertTrue(fixture.reader.blocked(7L, 99L));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
    }

    private enum FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcNoteBlockReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcNoteBlockReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
            assertTrue(transactions.active);
            assertEquals(Boolean.class, type);
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            return type.cast(Boolean.TRUE);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
        private RuntimeException startFailure;
        private RuntimeException completionFailure;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(3, definition.getTimeout());
            if (startFailure != null) throw startFailure;
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            commits++;
            if (completionFailure != null) throw completionFailure;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
        }
    }
}
