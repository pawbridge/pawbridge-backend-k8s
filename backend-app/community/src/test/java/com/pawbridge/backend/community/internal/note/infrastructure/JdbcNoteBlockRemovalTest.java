package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteBlockRemovalTest {
    private static final String INSERT = "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING";
    private static final String LOCK = "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE";
    private static final String DELETE = "DELETE FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?";

    @ParameterizedTest
    @MethodSource("memberPairs")
    void given__reverse_order_or_same_member_ids_and_no_outgoing_row__when__removing__then__insert_and_lock_each_sorted_distinct_member_before_one_guard_and_zero_row_delete(long current, long target, List<Long> expectedMembers) {
        var fixture = new Fixture();
        int[] guardCalls = {0};

        fixture.removal.remove(current, target, () -> {
            assertTrue(fixture.transactions.active);
            assertEquals(expectedMembers.size() * 2, fixture.jdbc.calls.size());
            guardCalls[0]++;
            fixture.events.add("guard");
        });

        var expectedEvents = new ArrayList<String>();
        expectedEvents.add("begin");
        for (int index = 0; index < expectedMembers.size(); index++) {
            long member = expectedMembers.get(index);
            Call insert = fixture.jdbc.calls.get(index * 2);
            Call lock = fixture.jdbc.calls.get(index * 2 + 1);
            assertEquals(INSERT, insert.sql());
            assertArrayEquals(new Object[]{member}, insert.arguments());
            assertEquals(LOCK, lock.sql());
            assertArrayEquals(new Object[]{member}, lock.arguments());
            expectedEvents.add("insert:" + member);
            expectedEvents.add("lock:" + member);
        }
        Call delete = fixture.jdbc.calls.getLast();
        assertEquals(DELETE, delete.sql());
        assertArrayEquals(new Object[]{current, target}, delete.arguments());
        expectedEvents.addAll(List.of("guard", "delete", "commit"));
        assertEquals(expectedEvents, fixture.events);
        assertEquals(expectedMembers.size() * 2 + 1, fixture.jdbc.calls.size());
        assertEquals(1, guardCalls[0]);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__begin_second_lock_guard_delete_or_commit_failure__when__removing__then__propagate_same_failure_stop_later_work_and_do_not_retry(FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private begin");
            case LOCK, DELETE -> new DataAccessResourceFailureException("private SQL");
            case GUARD -> new IllegalStateException("private guard");
            case COMMIT -> new TransactionSystemException("private commit");
        };
        if (phase == FailurePhase.BEGIN) fixture.transactions.beginFailure = failure;
        if (phase == FailurePhase.COMMIT) fixture.transactions.commitFailure = failure;
        if (phase == FailurePhase.LOCK || phase == FailurePhase.DELETE) {
            fixture.jdbc.failure = failure;
            fixture.jdbc.failAtCall = phase == FailurePhase.LOCK ? 4 : 5;
        }
        int[] guardCalls = {0};

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.removal.remove(9L, 3L, () -> {
            assertTrue(fixture.transactions.active);
            guardCalls[0]++;
            fixture.events.add("guard");
            if (phase == FailurePhase.GUARD) throw failure;
        })));

        List<String> prefix = List.of("begin", "insert:3", "lock:3", "insert:9", "lock:9");
        var expected = new ArrayList<>(phase == FailurePhase.BEGIN ? List.of("begin") : prefix);
        if (phase == FailurePhase.GUARD || phase == FailurePhase.DELETE || phase == FailurePhase.COMMIT) expected.add("guard");
        if (phase == FailurePhase.DELETE || phase == FailurePhase.COMMIT) expected.add("delete");
        if (phase != FailurePhase.BEGIN) expected.add(phase == FailurePhase.COMMIT ? "commit" : "rollback");
        assertEquals(expected, fixture.events);
        assertEquals(phase == FailurePhase.BEGIN ? 0 : phase == FailurePhase.LOCK || phase == FailurePhase.GUARD ? 4 : 5,
                fixture.jdbc.calls.size());
        assertEquals(phase == FailurePhase.BEGIN || phase == FailurePhase.LOCK ? 0 : 1, guardCalls[0]);
        assertEquals(phase == FailurePhase.COMMIT ? 1 : 0, fixture.transactions.commits);
        assertEquals(phase == FailurePhase.LOCK || phase == FailurePhase.GUARD || phase == FailurePhase.DELETE ? 1 : 0,
                fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    static Stream<Arguments> memberPairs() {
        return Stream.of(Arguments.of(9L, 3L, List.of(3L, 9L)), Arguments.of(7L, 7L, List.of(7L)));
    }

    private enum FailurePhase { BEGIN, LOCK, GUARD, DELETE, COMMIT }
    private record Call(String sql, Object[] arguments) {}

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcNoteBlockRemoval removal;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setTimeout(15);
            removal = new JdbcNoteBlockRemoval(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private final List<String> events;
        private final List<Call> calls = new ArrayList<>();
        private int failAtCall;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions, List<String> events) {
            this.transactions = transactions;
            this.events = events;
        }

        @Override
        public int update(String sql, Object... arguments) {
            String normalized = normalize(sql);
            record(normalized, arguments, normalized.startsWith("INSERT") ? "insert:" + arguments[0] : "delete");
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
            assertEquals(Long.class, requiredType);
            record(normalize(sql), arguments, "lock:" + arguments[0]);
            return requiredType.cast(arguments[0]);
        }

        private void record(String sql, Object[] arguments, String event) {
            assertTrue(transactions.active);
            calls.add(new Call(sql, arguments));
            events.add(event);
            if (calls.size() == failAtCall) throw failure;
        }

        private static String normalize(String sql) {
            return sql.replaceAll("\\s+", " ").strip();
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;
        private boolean active;
        private int commits;
        private int rollbacks;
        private RuntimeException beginFailure;
        private RuntimeException commitFailure;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertEquals(15, definition.getTimeout());
            assertFalse(definition.isReadOnly());
            assertFalse(active);
            events.add("begin");
            if (beginFailure != null) throw beginFailure;
            active = true;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            commits++;
            events.add("commit");
            if (commitFailure != null) throw commitFailure;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
            rollbacks++;
            events.add("rollback");
        }
    }
}
