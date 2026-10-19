package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

class JdbcNoteNotificationReaderTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final String JOIN = " FROM pawbridge_community.private_note_mailboxes m"
            + " JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id ";

    @ParameterizedTest
    @MethodSource("cursors")
    void given__optional_cursor_and_nullable_four_column_rows__when__listing_then_counting_unread__then__bind_owner_scoped_cursor_and_same_instant_in_separate_read_only_transactions(UUID cursor) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(new Row(new UUID(1L, 2L), Long.MAX_VALUE, NOW, NOW.minusSeconds(1)),
                new Row(new UUID(1L, 1L), null, Instant.parse("1969-12-31T23:59:59.000001Z"), null));

        assertEquals(fixture.jdbc.rows, fixture.reader.read(7L, cursor, NOW));
        assertFalse(fixture.transactions.active);
        assertEquals(5_000_000_000L, fixture.reader.unread(7L, NOW));

        assertEquals(List.of("begin", "list", "commit", "begin", "unread", "commit"), fixture.events);
        assertEquals(2, fixture.jdbc.calls.size());
        Call page = fixture.jdbc.calls.get(0);
        String cursorClause = cursor == null ? "" : " AND (m.created_at, m.note_id) < ("
                + " SELECT created_at, note_id FROM pawbridge_community.private_note_mailboxes"
                + " WHERE member_id = ? AND note_id = ? AND direction = 'INBOX' )";
        assertEquals("SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + JOIN
                + "WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?"
                + cursorClause + " ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21", normalize(page.sql()));
        assertArrayEquals(cursor == null ? new Object[]{7L, Timestamp.from(NOW)}
                : new Object[]{7L, Timestamp.from(NOW), 7L, cursor}, page.arguments());
        Call count = fixture.jdbc.calls.get(1);
        assertEquals("SELECT count(*)" + JOIN
                + "WHERE m.member_id = ? AND m.direction = 'INBOX' AND m.read_at IS NULL AND n.expires_at > ?",
                normalize(count.sql()));
        assertArrayEquals(new Object[]{7L, Timestamp.from(NOW)}, count.arguments());
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__null_unread_result__when__counting__then__fail_without_zero_fallback() {
        var fixture = new Fixture();
        fixture.jdbc.count = null;

        assertThrows(NullPointerException.class, () -> fixture.reader.unread(7L, NOW));

        assertEquals(List.of("begin", "unread", "commit"), fixture.events);
        assertEquals(1, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @MethodSource("representativeFailures")
    void given__begin_query_or_commit_failure_then_recovery__when__reading__then__propagate_same_failure_without_empty_zero_or_user_wrapping(Operation operation, FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private begin");
            case QUERY -> new DataAccessResourceFailureException("private SQL");
            case COMMIT -> new TransactionSystemException("private commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY -> fixture.jdbc.failure = failure;
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(fixture.reader, operation)));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.calls.size());
        assertEquals(phase == FailurePhase.QUERY ? 1 : 0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertEquals(operation == Operation.LIST ? fixture.jdbc.rows : fixture.jdbc.count,
                invoke(fixture.reader, operation));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    private static Stream<Arguments> cursors() {
        return Stream.of(Arguments.of((UUID) null), Arguments.of(new UUID(1L, 9L)));
    }

    private static Stream<Arguments> representativeFailures() {
        return Stream.of(Arguments.of(Operation.LIST, FailurePhase.BEGIN),
                Arguments.of(Operation.LIST, FailurePhase.QUERY), Arguments.of(Operation.UNREAD, FailurePhase.COMMIT));
    }

    private static Object invoke(JdbcNoteNotificationReader reader, Operation operation) {
        return operation == Operation.LIST ? reader.read(7L, null, NOW) : reader.unread(7L, NOW);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private enum Operation { LIST, UNREAD }
    private enum FailurePhase { BEGIN, QUERY, COMMIT }
    private record Call(String sql, Object[] arguments) {
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcNoteNotificationReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcNoteNotificationReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private List<Row> rows = List.of();
        private Long count = 5_000_000_000L;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            record("list", sql, arguments);
            var result = new ArrayList<T>();
            for (int index = 0; index < rows.size(); index++) {
                Row value = rows.get(index);
                ResultSet row = mock(ResultSet.class);
                try {
                    when(row.getObject("note_id", UUID.class)).thenReturn(value.noteId());
                    when(row.getObject("sender_id")).thenReturn(value.senderId());
                    when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(value.createdAt()));
                    when(row.getTimestamp("read_at")).thenReturn(value.readAt() == null ? null : Timestamp.from(value.readAt()));
                    result.add(rowMapper.mapRow(row, index));
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            record("unread", sql, arguments);
            return requiredType.cast(count);
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
