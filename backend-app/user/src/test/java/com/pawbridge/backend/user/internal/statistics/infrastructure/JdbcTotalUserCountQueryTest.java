package com.pawbridge.backend.user.internal.statistics.infrastructure;

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

class JdbcTotalUserCountQueryTest {
    @Test
    void given__physical_user_count_above_int_range__when__counting__then__query_only_id_column_once_and_return_raw_long_after_read_only_commit() {
        var fixture = new Fixture();

        assertEquals(5_000_000_000L, fixture.query.count());

        assertEquals("SELECT count(user_id) FROM pawbridge_user.users", fixture.jdbc.sql);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__unexpected_null_aggregate_then_recovery__when__counting__then__fail_instead_of_returning_zero_and_allow_the_next_read() {
        var fixture = new Fixture();
        fixture.jdbc.count = null;

        assertThrows(NullPointerException.class, fixture.query::count);

        assertEquals(1, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
        fixture.jdbc.count = 7L;

        assertEquals(7L, fixture.query.count());
        assertEquals(2, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__count_query_or_transaction_failure_then_recovery__when__counting__then__propagate_same_failure_without_zero_or_typed_contact_fallback(
            FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case QUERY_DAO -> new DataAccessResourceFailureException("synthetic-private-user-count-sql");
            case QUERY_RUNTIME -> new IllegalStateException("synthetic-private-count-runtime");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY_DAO, QUERY_RUNTIME -> fixture.jdbc.failure = failure;
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, fixture.query::count));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.queries);
        assertEquals(phase == FailurePhase.QUERY_DAO || phase == FailurePhase.QUERY_RUNTIME ? 1 : 0,
                fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertEquals(5_000_000_000L, fixture.query.count());
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
    }

    private enum FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcTotalUserCountQuery query;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            query = new JdbcTotalUserCountQuery(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private Long count = 5_000_000_000L;
        private String sql;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type) {
            assertTrue(transactions.active);
            assertEquals(Long.class, type);
            this.sql = sql;
            queries++;
            if (failure != null) throw failure;
            return type.cast(count);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
        private int rollbacks;
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
            rollbacks++;
        }
    }
}
