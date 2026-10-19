package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteFavoriteMutationTest {
    private static final UUID NOTE = UUID.fromString("f2700000-0000-0000-0000-000000000001");
    private static final Instant FIRST = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final String INSERT = "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING";
    private static final String MEMBER_LOCK = "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE";
    private static final String FIND = "SELECT 1 FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?";
    private static final String NOTE_LOCK = "SELECT note_id FROM pawbridge_community.private_notes WHERE note_id = ? FOR UPDATE";
    private static final String UPDATE = "UPDATE pawbridge_community.private_note_mailboxes SET favorite = ? WHERE member_id = ? AND note_id = ?";

    @ParameterizedTest
    @CsvSource({"false, 0", "true, 1"})
    void given__favorite_value_and_zero_or_one_updated_mailbox__when__setting__then__bind_two_distinct_times_and_hint_only_after_manager_commit(boolean favorite, int affected) {
        var fixture = new Fixture(); fixture.favorite = favorite; fixture.jdbc.affected = affected;

        fixture.set();

        assertEquals(List.of("begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
                "clock:2", "find:2", "update", "commit", "hint:7", "cleanup"), fixture.events);
        assertEquals(List.of(INSERT, MEMBER_LOCK, FIND, NOTE_LOCK, FIND, UPDATE), fixture.jdbc.calls.stream().map(Call::sql).toList());
        assertArrayEquals(new Object[]{7L}, fixture.jdbc.calls.get(0).arguments());
        assertArrayEquals(new Object[]{7L}, fixture.jdbc.calls.get(1).arguments());
        assertArrayEquals(new Object[]{7L, NOTE, Timestamp.from(FIRST)}, fixture.jdbc.calls.get(2).arguments());
        assertArrayEquals(new Object[]{NOTE}, fixture.jdbc.calls.get(3).arguments());
        assertArrayEquals(new Object[]{7L, NOTE, Timestamp.from(FIRST.plusSeconds(1))}, fixture.jdbc.calls.get(4).arguments());
        assertArrayEquals(new Object[]{favorite, 7L, NOTE}, fixture.jdbc.calls.get(5).arguments());
        assertEquals(1, fixture.guards); assertEquals(2, fixture.samples);
        assertEquals(List.of(7L), fixture.hinted); assertEquals(1, fixture.manager.commits); assertEquals(0, fixture.manager.rollbacks);
        fixture.assertReturned();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void given__ownership_absence_before_or_after_a_zero_row_note_lock__when__setting_favorite__then__roll_back_without_update_or_hint(int missingAt) {
        var fixture = new Fixture(); fixture.jdbc.found.clear();
        if (missingAt == 2) fixture.jdbc.found.add(true);
        fixture.jdbc.found.add(false); fixture.jdbc.noteLockRows = 0;

        assertThrows(NoteFavoriteMutation.MissingOwnedNote.class, fixture::set);

        var expected = new ArrayList<>(List.of("begin", "insert", "member-lock", "guard", "clock:1", "find:1"));
        if (missingAt == 2) expected.addAll(List.of("note-lock", "clock:2", "find:2"));
        expected.addAll(List.of("rollback", "cleanup"));
        assertEquals(expected, fixture.events); assertEquals(missingAt, fixture.samples);
        assertEquals(missingAt == 1 ? 3 : 5, fixture.jdbc.calls.size());
        assertEquals(1, fixture.guards); assertEquals(1, fixture.manager.rollbacks);
        assertEquals(0, fixture.manager.commits); assertTrue(fixture.hinted.isEmpty()); fixture.assertReturned();
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__begin_member_guard_lock_update_or_commit_failure__when__setting_favorite__then__propagate_without_retry_or_any_hint(FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic begin");
            case MEMBER_INSERT -> new DataIntegrityViolationException("synthetic member CHECK");
            case NOTE_LOCK, UPDATE -> new DataAccessResourceFailureException("synthetic SQL");
            case GUARD -> new IllegalStateException("synthetic guard");
            case COMMIT -> new TransactionSystemException("synthetic commit");
        };
        switch (phase) {
            case BEGIN -> fixture.manager.beginFailure = failure;
            case COMMIT -> fixture.manager.commitFailure = failure;
            case GUARD -> fixture.guardFailure = failure;
            default -> { fixture.jdbc.failEvent = phase == FailurePhase.MEMBER_INSERT ? "insert" : phase == FailurePhase.NOTE_LOCK ? "note-lock" : "update"; fixture.jdbc.failure = failure; }
        }

        assertSame(failure, assertThrows(RuntimeException.class, fixture::set));

        List<String> stages = List.of("begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
                "clock:2", "find:2", "update", "commit");
        int last = switch (phase) { case BEGIN -> 0; case MEMBER_INSERT -> 1; case GUARD -> 3; case NOTE_LOCK -> 6; case UPDATE -> 9; case COMMIT -> 10; };
        var expected = new ArrayList<>(stages.subList(0, last + 1));
        if (phase != FailurePhase.BEGIN && phase != FailurePhase.COMMIT) expected.add("rollback");
        if (phase != FailurePhase.BEGIN) expected.add("cleanup");
        assertEquals(expected, fixture.events); assertTrue(fixture.hinted.isEmpty());
        assertEquals(phase == FailurePhase.COMMIT ? 1 : 0, fixture.manager.commits);
        assertEquals(phase == FailurePhase.BEGIN || phase == FailurePhase.COMMIT ? 0 : 1, fixture.manager.rollbacks);
        fixture.assertReturned();
    }

    @Test
    void given__private_hint_runtime_failure_after_commit__when__notifying__then__keep_success_and_log_only_type_without_private_messages() {
        var fixture = new Fixture();
        fixture.hintFailure = new IllegalStateException("Bearer synthetic-private-token", new SQLException("synthetic-private-SQL"));
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcNoteFavoriteMutation.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            assertDoesNotThrow(fixture::set);

            assertEquals(List.of("commit", "hint:7", "cleanup"), fixture.events.subList(fixture.events.size() - 3, fixture.events.size()));
            assertEquals(1, fixture.manager.commits); assertEquals(0, fixture.manager.rollbacks); assertEquals(List.of(7L), fixture.hinted);
            assertEquals(1, appender.list.size()); var event = appender.list.getFirst();
            assertEquals("Private note push failed (IllegalStateException); REST restoration remains available", event.getFormattedMessage());
            assertNull(event.getThrowableProxy()); assertArrayEquals(new Object[]{"IllegalStateException"}, event.getArgumentArray());
            fixture.assertReturned();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private enum FailurePhase { BEGIN, MEMBER_INSERT, GUARD, NOTE_LOCK, UPDATE, COMMIT }
    private record Call(String sql, Object[] arguments) { }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final List<Long> hinted = new ArrayList<>();
        private final SynchronizingManager manager = new SynchronizingManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(manager, events);
        private final JdbcNoteFavoriteMutation mutation;
        private int guards, samples;
        private boolean favorite;
        private RuntimeException guardFailure, hintFailure;

        private Fixture() {
            var transaction = new TransactionTemplate(manager); transaction.setTimeout(15);
            mutation = new JdbcNoteFavoriteMutation(jdbc, transaction, current -> {
                // The commit marker proves ordering; Spring resources/actualTxActive may still be bound here.
                assertEquals(1, manager.commits); assertEquals("commit", events.getLast());
                events.add("hint:" + current); hinted.add(current);
                if (hintFailure != null) throw hintFailure;
            });
        }

        private void set() {
            Supplier<Instant> now = () -> { events.add("clock:" + ++samples); return FIRST.plusSeconds(samples - 1); };
            mutation.set(7L, NOTE, favorite, () -> {
                assertTrue(manager.active); events.add("guard"); guards++;
                if (guardFailure != null) throw guardFailure;
            }, now);
        }

        private void assertReturned() {
            assertFalse(manager.active);
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final SynchronizingManager manager;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private final ArrayDeque<Boolean> found = new ArrayDeque<>(List.of(true, true));
        private int finds, noteLockRows = 1, affected = 1;
        private String failEvent;
        private RuntimeException failure;

        private RecordingJdbc(SynchronizingManager manager, List<String> events) { this.manager = manager; this.events = events; }

        @Override public int update(String sql, Object... arguments) {
            record(sql, arguments, normalize(sql).startsWith("INSERT") ? "insert" : "update");
            return normalize(sql).startsWith("INSERT") ? 0 : affected;
        }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
            assertEquals(Long.class, type); record(sql, arguments, "member-lock"); return type.cast(arguments[0]);
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            boolean existence = normalize(sql).startsWith("SELECT 1 ");
            record(sql, arguments, existence ? "find:" + ++finds : "note-lock");
            if (existence ? !found.removeFirst() : noteLockRows == 0) return List.of();
            try { return List.of(mapper.mapRow(null, 0)); }
            catch (SQLException failure) { throw new AssertionError("Recording row mapper failed", failure); }
        }

        private void record(String sql, Object[] arguments, String event) {
            assertTrue(manager.active); calls.add(new Call(normalize(sql), arguments.clone())); events.add(event);
            if (event.equals(failEvent)) throw failure;
        }

        private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
    }

    /** Real Spring synchronization and lifecycle; recording commit/rollback are not database transaction evidence. */
    private static final class SynchronizingManager extends AbstractPlatformTransactionManager {
        private final List<String> events;
        private boolean active;
        private int commits, rollbacks;
        private RuntimeException beginFailure, commitFailure;

        private SynchronizingManager(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertEquals(15, definition.getTimeout()); assertFalse(definition.isReadOnly()); assertFalse(active);
            events.add("begin"); if (beginFailure != null) throw beginFailure; active = true;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit"); commits++; active = false; if (commitFailure != null) throw commitFailure;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); rollbacks++; active = false; }
        @Override protected void doCleanupAfterCompletion(Object transaction) { active = false; events.add("cleanup"); }
    }
}
