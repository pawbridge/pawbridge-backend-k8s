package com.pawbridge.backend.community.internal.homevideo.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcHomeVideoReaderTest {
    private static final Instant CUTOFF = Instant.parse("2026-09-20T15:00:00.123456Z");
    private static final Instant CREATED = Instant.parse("2026-09-01T16:20:30.654321Z");
    private static final Instant CHECKED = Instant.parse("2026-10-19T00:00:00.123456Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @ParameterizedTest
    @NullAndEmptySource
    void given__nullable_or_empty_metadata__when__reading__then__map_all_eleven_fields_and_bind_instant_without_local_time_conversion(String metadata)
            throws Exception {
        var fixture = new Fixture();
        Long duration = metadata == null ? null : 3_000_000_001L;
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(ID);
        when(row.getString("video_id")).thenReturn("abcdefghijk");
        when(row.getBoolean("published")).thenReturn(true);
        when(row.getInt("position")).thenReturn(2);
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(CREATED));
        when(row.getString("title")).thenReturn(metadata);
        when(row.getString("channel_title")).thenReturn(metadata);
        when(row.getString("thumbnail_url")).thenReturn(metadata);
        when(row.getObject("duration_seconds")).thenReturn(duration);
        when(row.getBoolean("available")).thenReturn(true);
        when(row.getTimestamp("checked_at")).thenReturn(Timestamp.from(CHECKED));
        fixture.jdbc.rows = List.of(row);

        assertEquals(List.of(new HomeVideo(ID, "abcdefghijk", true, 2, CREATED,
                metadata, metadata, metadata, duration, true, CHECKED)), fixture.reader.read(CUTOFF));

        assertArrayEquals(new Object[] {Timestamp.from(CUTOFF)}, fixture.jdbc.arguments);
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__no_visible_rows__when__reading__then__return_empty_list_after_read_only_completion() {
        var fixture = new Fixture();

        assertEquals(List.of(), fixture.reader.read(CUTOFF));

        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__query_failure_then_recovery__when__reading__then__propagate_failure_without_empty_fallback_and_release_transaction() {
        var fixture = new Fixture();
        var failure = new DataAccessResourceFailureException("synthetic-private-sql");
        fixture.jdbc.failure = failure;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> fixture.reader.read(CUTOFF)));
        assertEquals(1, fixture.transactions.rollbacks);
        assertEquals(0, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.jdbc.failure = null;

        assertEquals(List.of(), fixture.reader.read(CUTOFF));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_start_failure_then_recovery__when__reading__then__no_query_runs_before_successful_begin() {
        var fixture = new Fixture();
        var failure = new CannotCreateTransactionException("synthetic-private-begin");
        fixture.transactions.startFailure = failure;

        assertSame(failure, assertThrows(CannotCreateTransactionException.class, () -> fixture.reader.read(CUTOFF)));
        assertEquals(0, fixture.jdbc.queries);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;

        assertEquals(List.of(), fixture.reader.read(CUTOFF));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__transaction_completion_failure_then_recovery__when__reading__then__no_list_is_returned_before_successful_completion() {
        var fixture = new Fixture();
        var failure = new TransactionSystemException("synthetic-private-completion");
        fixture.transactions.completionFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> fixture.reader.read(CUTOFF)));
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
        fixture.transactions.completionFailure = null;

        assertEquals(List.of(), fixture.reader.read(CUTOFF));
        assertEquals(2, fixture.jdbc.queries);
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcHomeVideoReader reader;

        private Fixture() {
            var readOnlyTransaction = new TransactionTemplate(transactions);
            readOnlyTransaction.setReadOnly(true);
            readOnlyTransaction.setTimeout(3);
            reader = new JdbcHomeVideoReader(jdbc, readOnlyTransaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private List<ResultSet> rows = List.of();
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            assertTrue(transactions.active, "All JDBC mapping must complete inside the supplied read-only transaction.");
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            List<T> mapped = new ArrayList<>();
            try {
                for (int index = 0; index < rows.size(); index++) {
                    mapped.add(mapper.mapRow(rows.get(index), index));
                }
            } catch (SQLException failure) {
                throw new AssertionError("Synthetic row mapping failed", failure);
            }
            return mapped;
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
