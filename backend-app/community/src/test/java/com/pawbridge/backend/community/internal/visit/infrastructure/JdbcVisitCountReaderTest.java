package com.pawbridge.backend.community.internal.visit.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
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

class JdbcVisitCountReaderTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @ParameterizedTest
    @ValueSource(longs = {0L, 3_000_000_001L, Long.MAX_VALUE})
    void given__stored_count__when__reading__then__bind_exact_sql_date_and_preserve_long_after_read_only_completion(long count) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(count);

        assertEquals(count, fixture.reader.count(DAY));

        assertEquals("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?",
                fixture.jdbc.sql.replaceAll("\\s+", " ").strip());
        assertArrayEquals(new Object[] {Date.valueOf(DAY)}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__no_daily_row__when__reading__then__return_zero_after_read_only_completion() {
        var fixture = new Fixture();

        assertEquals(0L, fixture.reader.count(DAY));

        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__query_failure_then_recovery__when__reading__then__propagate_without_zero_fallback_and_release_transaction() {
        var fixture = new Fixture();
        var failure = new DataAccessResourceFailureException("synthetic-private-query");
        fixture.jdbc.failure = failure;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> fixture.reader.count(DAY)));
        assertEquals(1, fixture.transactions.rollbacks);
        assertEquals(0, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.jdbc.failure = null;
        fixture.jdbc.rows = List.of(77L);

        assertEquals(77L, fixture.reader.count(DAY));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_start_failure_then_recovery__when__reading__then__no_query_precedes_successful_begin() {
        var fixture = new Fixture();
        var failure = new CannotCreateTransactionException("synthetic-private-begin");
        fixture.transactions.startFailure = failure;

        assertSame(failure, assertThrows(CannotCreateTransactionException.class, () -> fixture.reader.count(DAY)));
        assertEquals(0, fixture.jdbc.queries);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;

        assertEquals(0L, fixture.reader.count(DAY));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_completion_failure_then_recovery__when__reading__then__no_count_is_returned_before_successful_completion() {
        var fixture = new Fixture();
        var failure = new TransactionSystemException("synthetic-private-completion");
        fixture.transactions.completionFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> fixture.reader.count(DAY)));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.transactions.completionFailure = null;
        fixture.jdbc.rows = List.of(77L);

        assertEquals(77L, fixture.reader.count(DAY));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcVisitCountReader reader;

        private Fixture() {
            var tx = new TransactionTemplate(transactions);
            tx.setReadOnly(true);
            tx.setTimeout(3);
            reader = new JdbcVisitCountReader(jdbc, tx);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private List<Long> rows = List.of();
        private String sql;
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type, Object... arguments) {
            assertTrue(transactions.active, "The daily count must be read inside the supplied read-only transaction.");
            assertEquals(Long.class, type);
            this.sql = sql;
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            return rows.stream().map(type::cast).toList();
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
