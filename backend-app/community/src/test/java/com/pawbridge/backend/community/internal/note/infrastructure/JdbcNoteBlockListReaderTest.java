package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

class JdbcNoteBlockListReaderTest {
    @Test
    void given__raw_long_count_and_timestamp_rows__when__counting_then_listing__then__use_two_completed_read_only_transactions_and_bind_owner_long_offset() {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(new Block(Long.MAX_VALUE, Instant.parse("2026-10-19T14:59:59.123456Z")),
                new Block(8L, Instant.parse("1969-12-31T23:59:59.000001Z")));

        assertEquals(5_000_000_000L, fixture.reader.count(7L));
        assertFalse(fixture.transactions.active);
        assertEquals(fixture.jdbc.rows, fixture.reader.read(7L, 10000));

        assertEquals(List.of("begin", "count", "commit", "begin", "page", "commit"), fixture.events);
        assertEquals(2, fixture.jdbc.calls.size());
        var count = fixture.jdbc.calls.get(0);
        assertEquals("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ?", count.sql().strip());
        assertArrayEquals(new Object[]{7L}, count.arguments());
        var page = fixture.jdbc.calls.get(1);
        assertEquals("SELECT blocked_id, created_at FROM pawbridge_community.private_note_blocks "
                + "WHERE member_id = ? ORDER BY created_at DESC, blocked_id DESC LIMIT 10 OFFSET ?", page.sql().replaceAll("\\s+", " ").strip());
        assertArrayEquals(new Object[]{7L, 100000L}, page.arguments());
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__no_outgoing_rows__when__listing__then__return_empty_only_after_successful_query_and_commit() {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of();

        assertEquals(List.of(), fixture.reader.read(7L, 0));

        assertEquals(List.of("begin", "page", "commit"), fixture.events);
        assertEquals(1, fixture.jdbc.calls.size());
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @MethodSource("failureCases")
    void given__query_or_transaction_failure_then_recovery__when__counting_or_listing__then__propagate_same_failure_without_zero_empty_or_user_wrapping(
            Operation operation, FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private block begin");
            case QUERY_DAO -> new DataAccessResourceFailureException("private block SQL");
            case QUERY_RUNTIME -> new IllegalStateException("private block runtime");
            case COMMIT -> new TransactionSystemException("private block commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY_DAO, QUERY_RUNTIME -> fixture.jdbc.failure = failure;
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(fixture.reader, operation)));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.calls.size());
        assertEquals(phase == FailurePhase.QUERY_DAO || phase == FailurePhase.QUERY_RUNTIME ? 1 : 0,
                fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertEquals(operation == Operation.COUNT ? Long.valueOf(5_000_000_000L) : fixture.jdbc.rows,
                invoke(fixture.reader, operation));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    private static Stream<Arguments> failureCases() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(FailurePhase.values())
                .map(phase -> Arguments.of(operation, phase)));
    }

    private static Object invoke(JdbcNoteBlockListReader reader, Operation operation) {
        return operation == Operation.COUNT ? reader.count(7L) : reader.read(7L, 0);
    }

    private enum Operation { COUNT, PAGE }
    private enum FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }
    private record Call(String sql, Object[] arguments) {
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcNoteBlockListReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcNoteBlockListReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private List<Block> rows = List.of(new Block(8L, Instant.EPOCH));
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            record("count", sql, arguments);
            return requiredType.cast(Long.valueOf(5_000_000_000L));
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            record("page", sql, arguments);
            var result = new ArrayList<T>();
            for (int index = 0; index < rows.size(); index++) {
                var row = mock(ResultSet.class);
                try {
                    when(row.getLong("blocked_id")).thenReturn(rows.get(index).memberId());
                    when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(rows.get(index).createdAt()));
                    result.add(rowMapper.mapRow(row, index));
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        private void record(String operation, String sql, Object[] arguments) {
            assertTrue(transactions.active);
            events.add(operation);
            calls.add(new Call(sql, arguments));
            if (failure != null) throw failure;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;
        private boolean active;
        private int commits;
        private int rollbacks;
        private RuntimeException startFailure;
        private RuntimeException completionFailure;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertFalse(active);
            assertTrue(definition.isReadOnly());
            assertEquals(3, definition.getTimeout());
            events.add("begin");
            if (startFailure != null) throw startFailure;
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            commits++;
            events.add("commit");
            if (completionFailure != null) throw completionFailure;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
            rollbacks++;
            events.add("rollback");
        }
    }
}
