package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

class JdbcNoteBlockStatusReaderTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void given__bounded_author_ids__when__reading__then__bind_owner_and_each_id_and_map_raw_longs_inside_supplied_read_only_transaction(int size) {
        var fixture = new Fixture();
        List<Long> ids = LongStream.range(0, size).map(index -> index == 0 ? Long.MAX_VALUE : 1000 - index).boxed().toList();
        fixture.jdbc.rows = List.of(Long.MAX_VALUE);

        assertEquals(List.of(Long.MAX_VALUE), fixture.reader.blockedAuthors(7L, ids));

        Object[] parameters = new Object[size + 1];
        parameters[0] = 7L;
        for (int index = 0; index < size; index++) parameters[index + 1] = ids.get(index);
        assertArrayEquals(parameters, fixture.jdbc.arguments);
        assertEquals(size + 1L, fixture.jdbc.sql.chars().filter(character -> character == '?').count());
        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @Test
    void given__no_outgoing_matches__when__reading__then__return_empty_result_after_successful_query_and_commit() {
        var fixture = new Fixture();
        fixture.jdbc.rows = List.of();

        assertEquals(List.of(), fixture.reader.blockedAuthors(7L, List.of(8L)));

        assertEquals(1, fixture.jdbc.queries);
        assertEquals(1, fixture.transactions.commits);
        assertFalse(fixture.transactions.active);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__block_query_or_transaction_failure_then_recovery__when__reading__then__propagate_same_failure_without_empty_fallback_or_user_wrapping(
            FailurePhase phase) {
        var fixture = new Fixture();
        RuntimeException failure = switch (phase) {
            case BEGIN -> new CannotCreateTransactionException("synthetic-private-begin");
            case QUERY_DAO -> new DataAccessResourceFailureException("synthetic-private-block-sql");
            case QUERY_RUNTIME -> new IllegalStateException("synthetic-private-block-runtime");
            case COMMIT -> new TransactionSystemException("synthetic-private-commit");
        };
        switch (phase) {
            case BEGIN -> fixture.transactions.startFailure = failure;
            case QUERY_DAO, QUERY_RUNTIME -> fixture.jdbc.failure = failure;
            case COMMIT -> fixture.transactions.completionFailure = failure;
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.reader.blockedAuthors(7L, List.of(8L))));

        assertEquals(phase == FailurePhase.BEGIN ? 0 : 1, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
        fixture.transactions.startFailure = null;
        fixture.transactions.completionFailure = null;
        fixture.jdbc.failure = null;

        assertEquals(List.of(8L), fixture.reader.blockedAuthors(7L, List.of(8L)));
        assertEquals(phase == FailurePhase.BEGIN ? 1 : 2, fixture.jdbc.queries);
        assertFalse(fixture.transactions.active);
    }

    private enum FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private static final class Fixture {
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final RecordingJdbc jdbc = new RecordingJdbc(transactions);
        private final JdbcNoteBlockStatusReader reader;

        private Fixture() {
            var transaction = new TransactionTemplate(transactions);
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
            reader = new JdbcNoteBlockStatusReader(jdbc, transaction);
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final RecordingTransactionManager transactions;
        private List<Long> rows = List.of(8L);
        private String sql;
        private Object[] arguments;
        private int queries;
        private RuntimeException failure;

        private RecordingJdbc(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
            assertTrue(transactions.active);
            this.sql = sql;
            this.arguments = arguments;
            queries++;
            if (failure != null) throw failure;
            var result = new ArrayList<T>();
            for (int index = 0; index < rows.size(); index++) {
                var row = mock(ResultSet.class);
                try {
                    when(row.getLong("blocked_id")).thenReturn(rows.get(index));
                    result.add(rowMapper.mapRow(row, index));
                } catch (SQLException exception) {
                    throw new AssertionError(exception);
                }
            }
            return result;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private boolean active;
        private int commits;
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
        }
    }
}
