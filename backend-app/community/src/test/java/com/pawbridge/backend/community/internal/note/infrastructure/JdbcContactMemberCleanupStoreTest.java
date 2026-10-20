package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcContactMemberCleanupStoreTest {
    private static final long MEMBER = 7;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenAbsentOrInstalledChatAndAffectedNotes__whenRemoving__thenKeepOriginalSqlOrderBoundIdsAndBatchBeforeCommitHint(boolean installed) {
        var fixture = new Fixture();
        fixture.jdbc.installed = installed;
        fixture.jdbc.notes = installed ? IntStream.rangeClosed(1, 301).mapToObj(i -> new UUID(17, i)).toList() : List.of();
        fixture.jdbc.rooms = installed ? List.of(new UUID(19, 1), new UUID(19, 2)) : List.of();

        fixture.store.remove(MEMBER, () -> fixture.events.add("policy"));

        var calls = fixture.jdbc.calls;
        assertCall(calls.get(0), "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING", MEMBER);
        assertCall(calls.get(1), "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE", MEMBER);
        assertEquals(3, fixture.events.indexOf("policy"));
        assertCall(calls.get(2), "SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ? ORDER BY note_id FOR UPDATE", MEMBER, MEMBER);
        assertCall(calls.get(3), "UPDATE pawbridge_community.private_note_requests SET request_hash = NULL WHERE note_id IN ( SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ? )", MEMBER, MEMBER);
        assertCall(calls.get(4), "UPDATE pawbridge_community.private_notes SET context_type = NULL, context_id = NULL WHERE sender_id = ? OR recipient_id = ?", MEMBER, MEMBER);
        assertCall(calls.get(5), "DELETE FROM pawbridge_community.private_note_members WHERE member_id = ?", MEMBER);
        int next = 6;
        if (installed) {
            for (int start : List.of(0, 300)) {
                List<UUID> batch = fixture.jdbc.notes.subList(start, Math.min(start + 300, 301));
                Call call = calls.get(next++);
                assertEquals("DELETE FROM pawbridge_community.private_notes n WHERE note_id IN (" + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ") AND NOT EXISTS( SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id )", call.sql());
                assertEquals(batch, call.arguments());
            }
        }
        assertCall(calls.get(next++), "SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL");
        if (installed) {
            assertCall(calls.get(next++), "SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?", MEMBER);
            assertCall(calls.get(next++), "DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?", MEMBER);
            assertCall(calls.get(next++), "DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?", MEMBER);
            assertCall(calls.get(next++), "UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?", MEMBER);
            assertCall(calls.get(next++), "UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?", MEMBER);
            assertCall(calls.get(next++), "UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?", MEMBER);
            for (UUID room : fixture.jdbc.rooms) {
                assertCall(calls.get(next++), "DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS( SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)", room);
            }
        }
        assertEquals(next, calls.size());
        assertEquals(List.of("commit", "hint:7", "cleanup"), fixture.events.subList(fixture.events.size() - 3, fixture.events.size()));
        assertEquals(1, fixture.transactions.commitAttempts);
        assertEquals(0, fixture.transactions.rollbacks);
        assertEquals(1, fixture.hints);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void givenPolicyNoteSqlInstallationQueryOrSynchronousChatFailure__whenRemoving__thenPropagateRollbackAndNeverCallCommittedHint(FailurePhase phase) {
        var fixture = new Fixture();
        fixture.jdbc.installed = true;
        fixture.jdbc.notes = List.of(new UUID(17, 1));
        fixture.jdbc.rooms = List.of(new UUID(19, 1));
        RuntimeException failure = phase == FailurePhase.POLICY ? new IllegalStateException("private policy") : new DataAccessResourceFailureException("private SQL");
        fixture.jdbc.failure = failure;
        fixture.jdbc.failPrefix = switch (phase) {
            case POLICY -> null;
            case NOTES -> "SELECT note_id FROM pawbridge_community.private_notes";
            case INSTALLATION -> "SELECT to_regclass";
            case CHAT -> "UPDATE pawbridge_community.member_chat_messages";
        };

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.store.remove(MEMBER, () -> {
            fixture.events.add("policy");
            if (phase == FailurePhase.POLICY) throw failure;
        })));

        assertEquals(List.of("rollback", "cleanup"), fixture.events.subList(fixture.events.size() - 2, fixture.events.size()));
        assertEquals(0, fixture.transactions.commitAttempts);
        assertEquals(1, fixture.transactions.rollbacks);
        assertEquals(0, fixture.hints);
        assertFalse(fixture.transactions.active);
        if (phase == FailurePhase.POLICY) assertEquals(2, fixture.jdbc.calls.size());
        else assertTrue(fixture.jdbc.calls.getLast().sql().startsWith(fixture.jdbc.failPrefix));
    }

    @Test
    void givenCommitFailure__whenRemoving__thenPropagateExactFailureAndDoNotSendHint() {
        var fixture = new Fixture();
        var failure = new TransactionSystemException("private commit");
        fixture.transactions.commitFailure = failure;

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> fixture.store.remove(MEMBER, () -> fixture.events.add("policy"))));

        assertEquals(1, fixture.transactions.commitAttempts);
        assertEquals(0, fixture.hints);
        assertFalse(fixture.transactions.committed);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void givenCommittedHintRuntimeFailure__whenRemoving__thenKeepCommitAndLogOnlyFailureType() {
        var fixture = new Fixture();
        fixture.hintFailure = new IllegalStateException("private hint payload");
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcContactMemberCleanupStore.class);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            fixture.store.remove(MEMBER, () -> fixture.events.add("policy"));

            assertEquals(1, fixture.transactions.commitAttempts);
            assertTrue(fixture.transactions.committed);
            assertEquals(1, fixture.hints);
            assertEquals(1, appender.list.size());
            assertEquals("Private note push failed (IllegalStateException); REST restoration remains available", appender.list.getFirst().getFormattedMessage());
            assertNull(appender.list.getFirst().getThrowableProxy());
            assertFalse(fixture.transactions.active);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static void assertCall(Call call, String sql, Object... arguments) {
        assertEquals(sql, call.sql());
        assertEquals(Arrays.asList(arguments), call.arguments());
    }

    private enum FailurePhase { POLICY, NOTES, INSTALLATION, CHAT }
    private record Call(String sql, List<Object> arguments) {}

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        final JdbcContactMemberCleanupStore store;
        RuntimeException hintFailure;
        int hints;

        Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setTimeout(15);
            store = new JdbcContactMemberCleanupStore(jdbc, transaction, memberId -> {
                assertTrue(transactions.committed);
                hints++;
                events.add("hint:" + memberId);
                if (hintFailure != null) throw hintFailure;
            });
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        final RecordingTransactionManager transactions;
        final List<String> events;
        final List<Call> calls = new ArrayList<>();
        List<UUID> notes = List.of();
        List<UUID> rooms = List.of();
        boolean installed;
        String failPrefix;
        RuntimeException failure;

        RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override public int update(String sql, Object... arguments) {
            record(sql, arguments);
            return 0;
        }

        @Override public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
            record(sql, arguments);
            assertEquals(Long.class, type);
            return type.cast(arguments[0]);
        }

        @Override public <T> T queryForObject(String sql, Class<T> type) {
            record(sql, new Object[0]);
            assertEquals(Boolean.class, type);
            return type.cast(installed);
        }

        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
            record(sql, arguments);
            return (List<T>) notes;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... arguments) {
            record(sql, arguments);
            assertEquals(UUID.class, type);
            return (List<T>) rooms;
        }

        private void record(String sql, Object[] arguments) {
            assertTrue(transactions.active);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String normalized = sql.replaceAll("\\s+", " ").strip();
            calls.add(new Call(normalized, List.copyOf(Arrays.asList(arguments))));
            events.add("sql");
            if (failPrefix != null && normalized.startsWith(failPrefix)) throw failure;
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> events;
        boolean active;
        boolean committed;
        int commitAttempts;
        int rollbacks;
        RuntimeException commitFailure;

        RecordingTransactionManager(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {
            assertEquals(15, definition.getTimeout());
            assertFalse(definition.isReadOnly());
            assertFalse(active);
            active = true;
            events.add("begin");
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            commitAttempts++;
            if (commitFailure != null) throw commitFailure;
            committed = true;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
            rollbacks++;
        }
        @Override protected void doCleanupAfterCompletion(Object tx) {
            active = false;
            events.add("cleanup");
        }
    }
}
