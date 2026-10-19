package com.pawbridge.backend.user.internal.favorite.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcFavoriteStatusQueryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void given__matching_or_absent_favorite__when__checking__then__two_id_exists_query_returns_boolean_after_read_only_commit(boolean exists) {
        var fixture = new Fixture();
        fixture.jdbc.exists = exists;

        assertEquals(exists, fixture.query.isFavorite(52L, 73L));

        assertEquals("SELECT EXISTS ( SELECT 1 FROM pawbridge_user.favorites WHERE user_id = ? AND animal_id = ? )",
                fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertArrayEquals(new Object[] {52L, 73L}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
    void given__zero_negative_or_boundary_animal_id__when__checking__then__exact_long_is_queried_without_positive_validation(long animalId) {
        var fixture = new Fixture();

        assertFalse(fixture.query.isFavorite(52L, animalId));

        assertArrayEquals(new Object[] {52L, animalId}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__jdbc_failure_then_recovery__when__checking__then__failure_rolls_back_without_false_fallback_and_next_query_commits() {
        var fixture = new Fixture();
        var failure = new DataAccessResourceFailureException("synthetic-private-sql");
        fixture.jdbc.failure = failure;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> fixture.query.isFavorite(52L, 73L)));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(1, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.jdbc.failure = null;
        fixture.jdbc.exists = true;

        assertTrue(fixture.query.isFavorite(52L, 73L));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_start_failure_then_recovery__when__checking__then__no_query_precedes_failure_and_next_query_commits() {
        var fixture = new Fixture();
        var failure = new CannotCreateTransactionException("synthetic-private-start");
        fixture.transactions.startFailure = failure;

        assertSame(failure, assertThrows(CannotCreateTransactionException.class, () -> fixture.query.isFavorite(52L, 73L)));
        assertEquals(0, fixture.jdbc.queries);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;

        assertFalse(fixture.query.isFavorite(52L, 73L));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_completion_failure_then_recovery__when__checking__then__no_boolean_is_returned_before_successful_completion() {
        var fixture = new Fixture();
        var failure = new TransactionSystemException("synthetic-private-completion");
        fixture.transactions.completionFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> fixture.query.isFavorite(52L, 73L)));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.transactions.completionFailure = null;
        fixture.jdbc.exists = true;

        assertTrue(fixture.query.isFavorite(52L, 73L));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcFavoriteStatusQuery query;

        private Fixture() {
            var readOnlyTransaction = new TransactionTemplate(transactions);
            readOnlyTransaction.setReadOnly(true);
            readOnlyTransaction.setTimeout(3);
            query = new JdbcFavoriteStatusQuery(jdbc, readOnlyTransaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private String sql;
        private Object[] arguments;
        private int queries;
        private boolean exists;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) { this.transactions = transactions; }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
            assertTrue(transactions.active, "The existence query must run inside the existing read-only transaction.");
            this.sql = sql;
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            return type.cast(exists);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
        private int rollbacks;
        private RuntimeException startFailure;
        private RuntimeException completionFailure;

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(3, definition.getTimeout());
            if (startFailure != null) throw startFailure;
            active = true;
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {
            active = false;
            commits++;
            if (completionFailure != null) throw completionFailure;
        }
        @Override public void rollback(TransactionStatus status) {
            active = false;
            rollbacks++;
        }
    }
}
