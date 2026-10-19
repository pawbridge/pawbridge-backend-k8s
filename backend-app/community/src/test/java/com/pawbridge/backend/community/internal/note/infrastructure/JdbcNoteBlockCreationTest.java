package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteBlockCreationTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final String MEMBER_INSERT = "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING";
    private static final String LOCK = "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE";
    private static final String BLOCK_INSERT = "INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";

    @Test
    void given__reverse_order_ids_and_zero_insert_counts__when__creating__then__lock_each_sorted_member_before_one_supplier_and_original_timestamp_insert() {
        var fixture = new Fixture();
        int[] supplierCalls = {0};

        fixture.creation.create(9L, 3L, () -> {
            assertTrue(fixture.transactions.active);
            assertEquals(4, fixture.jdbc.calls.size());
            supplierCalls[0]++;
            fixture.events.add("supplier");
            return NOW;
        });

        for (int index = 0; index < 2; index++) {
            long member = index == 0 ? 3L : 9L;
            Call insert = fixture.jdbc.calls.get(index * 2);
            Call lock = fixture.jdbc.calls.get(index * 2 + 1);
            assertEquals(MEMBER_INSERT, insert.sql());
            assertArrayEquals(new Object[]{member}, insert.arguments());
            assertEquals(LOCK, lock.sql());
            assertArrayEquals(new Object[]{member}, lock.arguments());
        }
        Call block = fixture.jdbc.calls.getLast();
        assertEquals(BLOCK_INSERT, block.sql());
        assertArrayEquals(new Object[]{9L, 3L, Timestamp.from(NOW)}, block.arguments());
        assertEquals(NOW, ((Timestamp) block.arguments()[2]).toInstant());
        assertEquals(List.of("begin", "insert:3", "lock:3", "insert:9", "lock:9", "supplier", "block", "commit"), fixture.events);
        assertEquals(5, fixture.jdbc.calls.size());
        assertEquals(1, supplierCalls[0]);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__begin_second_lock_supplier_insert_or_commit_failure__when__creating__then__propagate_same_failure_stop_later_work_and_do_not_retry(FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("private begin");
            case LOCK, INSERT -> new DataAccessResourceFailureException("private SQL");
            case SUPPLIER -> new IllegalStateException("private supplier");
            case COMMIT -> new TransactionSystemException("private commit");
        };
        if (phase == FailurePhase.BEGIN) fixture.transactions.beginFailure = failure;
        if (phase == FailurePhase.COMMIT) fixture.transactions.commitFailure = failure;
        if (phase == FailurePhase.LOCK || phase == FailurePhase.INSERT) {
            fixture.jdbc.failure = failure;
            fixture.jdbc.failAtCall = phase == FailurePhase.LOCK ? 4 : 5;
        }
        int[] supplierCalls = {0};

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.creation.create(9L, 3L, () -> {
            assertTrue(fixture.transactions.active);
            supplierCalls[0]++;
            fixture.events.add("supplier");
            if (phase == FailurePhase.SUPPLIER) throw failure;
            return NOW;
        })));

        List<String> prefix = List.of("begin", "insert:3", "lock:3", "insert:9", "lock:9");
        var expected = new ArrayList<>(phase == FailurePhase.BEGIN ? List.of("begin") : prefix);
        if (phase == FailurePhase.SUPPLIER || phase == FailurePhase.INSERT || phase == FailurePhase.COMMIT) expected.add("supplier");
        if (phase == FailurePhase.INSERT || phase == FailurePhase.COMMIT) expected.add("block");
        if (phase != FailurePhase.BEGIN) expected.add(phase == FailurePhase.COMMIT ? "commit" : "rollback");
        assertEquals(expected, fixture.events);
        assertEquals(phase == FailurePhase.BEGIN ? 0 : phase == FailurePhase.LOCK || phase == FailurePhase.SUPPLIER ? 4 : 5,
                fixture.jdbc.calls.size());
        assertEquals(phase == FailurePhase.BEGIN || phase == FailurePhase.LOCK ? 0 : 1, supplierCalls[0]);
        assertEquals(phase == FailurePhase.COMMIT ? 1 : 0, fixture.transactions.commits);
        assertEquals(phase == FailurePhase.LOCK || phase == FailurePhase.SUPPLIER || phase == FailurePhase.INSERT ? 1 : 0,
                fixture.transactions.rollbacks);
        assertFalse(fixture.transactions.active);
    }

    private enum FailurePhase { BEGIN, LOCK, SUPPLIER, INSERT, COMMIT }
    private record Call(String sql, Object[] arguments) {}

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final RecordingTransactionManager transactions = new RecordingTransactionManager(events);
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions, events);
        private final JdbcNoteBlockCreation creation;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setTimeout(15);
            creation = new JdbcNoteBlockCreation(jdbc, transaction);
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
            String event = normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")
                    ? "insert:" + arguments[0] : "block";
            record(normalized, arguments, event);
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
            calls.add(new Call(sql, arguments.clone()));
            events.add(event);
            if (calls.size() == failAtCall) throw failure;
        }

        private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;
        private boolean active;
        private int commits;
        private int rollbacks;
        private RuntimeException beginFailure;
        private RuntimeException commitFailure;

        private RecordingTransactionManager(List<String> events) { this.events = events; }

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
        public void rollback(TransactionStatus status) { active = false; rollbacks++; events.add("rollback"); }
    }
}
