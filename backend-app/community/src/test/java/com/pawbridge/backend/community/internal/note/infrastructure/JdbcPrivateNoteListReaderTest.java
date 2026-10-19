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

class JdbcPrivateNoteListReaderTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final String JOIN = " FROM pawbridge_community.private_note_mailboxes m"
            + " JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id ";
    private static final String FILTER = "WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?";

    @ParameterizedTest
    @MethodSource("queryInputs")
    void given__box_favorite_page_and_nullable_ten_column_row__when__counting_then_listing__then__bind_same_instant_and_long_offset_with_separate_read_only_transactions(String box, boolean favorites, int page, StoredNote note) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(note);

        assertEquals(5_000_000_000L, fixture.reader.count(7L, box, favorites, NOW));
        assertFalse(fixture.transactions.active);
        assertEquals(List.of(note), fixture.reader.read(7L, box, favorites, page, NOW));

        assertEquals(List.of("begin", "count", "commit", "begin", "list", "commit"), fixture.events);
        assertEquals(2, fixture.jdbc.calls.size());
        Call count = fixture.jdbc.calls.get(0);
        assertEquals("SELECT count(*)" + JOIN + FILTER, normalize(count.sql()));
        assertArrayEquals(new Object[]{7L, box, favorites, Timestamp.from(NOW)}, count.arguments());
        Call list = fixture.jdbc.calls.get(1);
        assertEquals("SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,"
                + " n.created_at, m.direction, m.read_at, m.favorite" + JOIN + FILTER
                + " ORDER BY m.created_at DESC, m.note_id DESC LIMIT 10 OFFSET ?", normalize(list.sql()));
        assertArrayEquals(new Object[]{7L, box, favorites, Timestamp.from(NOW), (long) page * 10}, list.arguments());
        assertEquals(2, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__empty_owner_scoped_result__when__listing__then__return_empty_after_commit_without_extra_count_or_find() {
        var fixture = new Fixture();

        assertEquals(List.of(), fixture.reader.read(7L, "INBOX", false, 0, NOW));

        assertEquals(List.of("begin", "list", "commit"), fixture.events);
        assertEquals(1, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @MethodSource("representativeFailures")
    void given__begin_query_or_commit_failure_then_recovery__when__counting_or_listing__then__propagate_same_failure_without_zero_empty_or_user_wrapping(Operation operation, FailurePhase phase) {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of(new StoredNote(new UUID(23L, 1L), 8L, 7L, " 原文 ", null, null, NOW, "INBOX", null, false));
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private begin");
            case QUERY -> new DataAccessResourceFailureException("private query");
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

        assertEquals(operation == Operation.COUNT ? fixture.jdbc.count : fixture.jdbc.rows, invoke(fixture.reader, operation));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.calls.size());
        assertFalse(fixture.transactions.active);
    }

    static Stream<Arguments> queryInputs() {
        return Stream.of(Arguments.of("INBOX", false, 0, new StoredNote(new UUID(23L, 1L), null, 7L,
                        " 原文\n본문 ", null, null, Instant.parse("1969-12-31T23:59:59.000001Z"), "INBOX", null, false)),
                Arguments.of("SENT", true, 10000, new StoredNote(new UUID(23L, 2L), 7L, null,
                        " 보낸 원문 ", "REPORT", Long.MAX_VALUE, NOW, "SENT", NOW.minusSeconds(1), true)));
    }

    static Stream<Arguments> representativeFailures() {
        return Stream.of(Arguments.of(Operation.COUNT, FailurePhase.BEGIN), Arguments.of(Operation.LIST, FailurePhase.QUERY),
                Arguments.of(Operation.LIST, FailurePhase.COMMIT));
    }

    private static Object invoke(JdbcPrivateNoteListReader reader, Operation operation) {
        return operation == Operation.COUNT ? reader.count(7L, "INBOX", false, NOW) : reader.read(7L, "INBOX", false, 0, NOW);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private enum Operation { COUNT, LIST }
    private enum FailurePhase { BEGIN, QUERY, COMMIT }
    private record Call(String sql, Object[] arguments) {}

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcPrivateNoteListReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcPrivateNoteListReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private List<StoredNote> rows = List.of();
        private Long count = 5_000_000_000L;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            record("count", sql, arguments);
            return requiredType.cast(count);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            record("list", sql, arguments);
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
