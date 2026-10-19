package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteExpiryTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456789Z");
    private static final String NOTES_SQL = "DELETE FROM pawbridge_community.private_notes WHERE note_id IN ( SELECT note_id FROM pawbridge_community.private_notes WHERE expires_at <= ? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED )";
    private static final String REQUESTS_SQL = "DELETE FROM pawbridge_community.private_note_requests WHERE (sender_id, request_id) IN ( SELECT sender_id, request_id FROM pawbridge_community.private_note_requests WHERE note_id IS NULL AND created_at < ? ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED )";

    @ParameterizedTest
    @ValueSource(ints = {0, 17})
    void given__zero_or_nonzero_expired_notes__when__expiring__then__sample_once_inside_transaction_delete_both_batches_and_return_only_notes(int deletedNotes) {
        var fixture = new Fixture();
        fixture.jdbc.deletedNotes = deletedNotes;

        assertEquals(deletedNotes, fixture.expiry.expire());

        assertEquals(List.of("begin", "clock", "notes", "requests", "commit", "cleanup"), fixture.events);
        assertEquals(List.of(NOTES_SQL, REQUESTS_SQL), fixture.jdbc.calls.stream().map(Call::sql).toList());
        assertArrayEquals(new Object[]{Timestamp.from(NOW)}, fixture.jdbc.calls.get(0).arguments());
        assertArrayEquals(new Object[]{Timestamp.from(NOW.minusSeconds(172800))}, fixture.jdbc.calls.get(1).arguments());
        assertEquals(1, fixture.manager.commits);
        assertEquals(0, fixture.manager.rollbacks);
        fixture.assertReturned();
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__clock_or_either_delete_fails__when__expiring__then__propagate_the_same_failure_without_later_sql_and_finish_rollback(FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = phase == FailurePhase.CLOCK
                ? new IllegalStateException("synthetic clock")
                : new DataAccessResourceFailureException("synthetic SQL");
        if (phase == FailurePhase.CLOCK) fixture.clockFailure = failure;
        else {
            fixture.jdbc.failedStatement = phase == FailurePhase.NOTES ? "notes" : "requests";
            fixture.jdbc.failure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, fixture.expiry::expire));

        var expected = new ArrayList<>(List.of("begin", "clock"));
        if (phase != FailurePhase.CLOCK) expected.add("notes");
        if (phase == FailurePhase.REQUESTS) expected.add("requests");
        expected.addAll(List.of("rollback", "cleanup"));
        assertEquals(expected, fixture.events);
        assertEquals(phase == FailurePhase.CLOCK ? 0 : phase == FailurePhase.NOTES ? 1 : 2, fixture.jdbc.calls.size());
        assertEquals(0, fixture.manager.commits);
        assertEquals(1, fixture.manager.rollbacks);
        fixture.assertReturned();
    }

    private enum FailurePhase { CLOCK, NOTES, REQUESTS }
    private record Call(String sql, Object[] arguments) { }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final Manager manager = new Manager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(manager, events);
        private final Clock clock = mock(Clock.class);
        private final JdbcNoteExpiry expiry;
        private RuntimeException clockFailure;

        private Fixture() {
            when(clock.instant()).thenAnswer(call -> {
                assertTrue(manager.active);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                events.add("clock");
                if (clockFailure != null) throw clockFailure;
                return NOW;
            });
            var transaction = new TransactionTemplate(manager);
            transaction.setTimeout(15);
            expiry = new JdbcNoteExpiry(jdbc, transaction, clock);
        }

        private void assertReturned() {
            verify(clock).instant();
            verifyNoMoreInteractions(clock);
            assertFalse(manager.active);
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final Manager manager;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private int deletedNotes;
        private String failedStatement;
        private RuntimeException failure;

        private RecordingJdbc(Manager manager, List<String> events) {
            this.manager = manager;
            this.events = events;
        }

        @Override
        public int update(String sql, Object... arguments) {
            assertTrue(manager.active);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String normalized = sql.replaceAll("\\s+", " ").strip();
            String statement;
            if (NOTES_SQL.equals(normalized)) statement = "notes";
            else {
                assertEquals(REQUESTS_SQL, normalized);
                statement = "requests";
            }
            calls.add(new Call(normalized, arguments.clone()));
            events.add(statement);
            if (statement.equals(failedStatement)) throw failure;
            return statement.equals("notes") ? deletedNotes : 11;
        }
    }

    /** Real Spring synchronization with recording JDBC/TM; not database rollback, row-lock or FK evidence. */
    private static final class Manager extends AbstractPlatformTransactionManager {
        private final List<String> events;
        private boolean active;
        private int commits, rollbacks;

        private Manager(List<String> events) {
            this.events = events;
        }

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertEquals(15, definition.getTimeout());
            assertFalse(definition.isReadOnly());
            events.add("begin");
            active = true;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            commits++;
            active = false;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
            rollbacks++;
            active = false;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            active = false;
            events.add("cleanup");
        }
    }
}
