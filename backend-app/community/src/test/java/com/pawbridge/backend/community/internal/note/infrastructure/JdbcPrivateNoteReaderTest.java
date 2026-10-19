package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

class JdbcPrivateNoteReaderTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final UUID NOTE_ID = new UUID(1L, 9L);

    @ParameterizedTest
    @MethodSource("storedNotes")
    void given__nullable_ten_column_row__when__finding__then__bind_owner_uuid_and_strict_instant_and_map_raw_values_in_one_read_only_transaction(StoredNote note) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(note);

        assertEquals(Optional.of(note), fixture.reader.find(7L, NOTE_ID, NOW));

        assertEquals(List.of("begin", "find", "commit"), fixture.events);
        assertEquals(1, fixture.jdbc.calls.size());
        Call call = fixture.jdbc.calls.getFirst();
        assertEquals("SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,"
                + " n.created_at, m.direction, m.read_at, m.favorite"
                + " FROM pawbridge_community.private_note_mailboxes m"
                + " JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id"
                + " WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?", normalize(call.sql()));
        assertArrayEquals(new Object[]{7L, NOTE_ID, Timestamp.from(NOW)}, call.arguments());
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__no_owner_scoped_row__when__finding__then__return_empty_after_commit_without_second_query() {
        var fixture = new Fixture();

        assertEquals(Optional.empty(), fixture.reader.find(7L, NOTE_ID, NOW));

        assertEquals(List.of("begin", "find", "commit"), fixture.events);
        assertEquals(1, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__begin_query_or_commit_failure_then_recovery__when__finding__then__propagate_same_failure_without_empty_or_user_wrapping(FailurePhase phase) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(storedNotes().findFirst().orElseThrow());
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

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.reader.find(7L, NOTE_ID, NOW)));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.calls.size());
        assertEquals(phase == FailurePhase.QUERY ? 1 : 0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertEquals(Optional.of(fixture.jdbc.rows.getFirst()), fixture.reader.find(7L, NOTE_ID, NOW));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    private static Stream<StoredNote> storedNotes() {
        return Stream.of(new StoredNote(NOTE_ID, null, 7L, " 원문\n본문 ", null, null,
                        Instant.parse("1969-12-31T23:59:59.000001Z"), "INBOX", null, false),
                new StoredNote(NOTE_ID, 7L, null, "", "REPORT", Long.MAX_VALUE,
                        NOW, "SENT", NOW.minusSeconds(1), true));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private enum FailurePhase { BEGIN, QUERY, COMMIT }
    private record Call(String sql, Object[] arguments) {}

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcPrivateNoteReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcPrivateNoteReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private List<StoredNote> rows = List.of();
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            assertTrue(transactions.active);
            events.add("find");
            calls.add(new Call(sql, arguments));
            if (failure != null) throw failure;
            var result = new ArrayList<T>();
            for (int index = 0; index < rows.size(); index++) {
                StoredNote value = rows.get(index);
                ResultSet row = mock(ResultSet.class);
                try {
                    when(row.getObject("note_id", UUID.class)).thenReturn(value.noteId());
                    when(row.getObject("sender_id")).thenReturn(value.senderId());
                    when(row.getObject("recipient_id")).thenReturn(value.recipientId());
                    when(row.getString("body")).thenReturn(value.body());
                    when(row.getString("context_type")).thenReturn(value.contextType());
                    when(row.getObject("context_id")).thenReturn(value.contextId());
                    when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(value.createdAt()));
                    when(row.getString("direction")).thenReturn(value.direction());
                    when(row.getTimestamp("read_at")).thenReturn(value.readAt() == null ? null : Timestamp.from(value.readAt()));
                    when(row.getBoolean("favorite")).thenReturn(value.favorite());
                    result.add(rowMapper.mapRow(row, index));
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            }
            return result;
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
