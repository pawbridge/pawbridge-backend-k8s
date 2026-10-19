package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcPostDeletionTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final Connection connection = mock(Connection.class);
    private final ResultSet result = mock(ResultSet.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:05:10.123456789Z"), ZoneId.of("Asia/Seoul"));
    private final List<StatementCall> calls = new ArrayList<>();
    private boolean transactionActive;
    private int failAt;
    private int updateCount = 1;
    private final RuntimeException failure = new IllegalStateException("synthetic DB failure");

    @BeforeEach
    void prepareTransactionAndStatements() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Void>>any())).thenAnswer(call -> {
            TransactionCallback<Void> callback = call.getArgument(0);
            transactionActive = true;
            try { return callback.doInTransaction(new SimpleTransactionStatus()); }
            finally { transactionActive = false; }
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            assertTrue(transactionActive);
            StatementCall statementCall = new StatementCall(call.getArgument(0));
            calls.add(statementCall);
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(binding -> { statementCall.values.put(binding.getArgument(0), binding.getArgument(1)); return null; })
                    .when(statement).setObject(anyInt(), any());
            doAnswer(binding -> {
                statementCall.values.put(binding.getArgument(0), binding.getArgument(1));
                statementCall.calendars.put(binding.getArgument(0), binding.getArgument(2));
                return null;
            }).when(statement).setTimestamp(anyInt(), any(Timestamp.class), any(Calendar.class));
            return statement;
        });
        when(result.next()).thenReturn(true);
        when(result.getLong("author_id")).thenReturn(7L);
        when(jdbc.query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any()))
                .thenAnswer(call -> {
                    call.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
                    if (failAt == calls.size()) throw failure;
                    return call.getArgument(1, ResultSetExtractor.class).extractData(result);
                });
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(call -> {
            call.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            if (failAt == calls.size()) throw failure;
            return updateCount;
        });
    }

    @Test
    void given__own_active_post__when__delete__then__lock_and_three_mutations_share_transaction_with_exact_event() throws Exception {
        deletion().delete(42L, 7L);
        assertFalse(transactionActive);
        assertEquals(4, calls.size());
        var lock = calls.get(0);
        assertTrue(lock.sql.contains("pawbridge_community.posts"));
        assertTrue(lock.sql.contains("deleted_at IS NULL"));
        assertTrue(lock.sql.contains("FOR UPDATE"));
        assertEquals(42L, lock.values.get(1));
        var source = calls.get(1);
        assertTrue(source.sql.contains("SET deleted_at = ?, updated_at = ?"));
        assertTrue(source.sql.contains("post_id = ? AND author_id = ? AND deleted_at IS NULL"));
        assertFalse(source.sql.contains("title ="));
        assertFalse(source.sql.contains("image_urls ="));
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values.get(1));
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values.get(2));
        assertEquals(42L, source.values.get(3));
        assertEquals(7L, source.values.get(4));
        assertEquals("UTC", source.calendars.get(1).getTimeZone().getID());
        assertEquals("UTC", source.calendars.get(2).getTimeZone().getID());
        var outbox = calls.get(2);
        assertTrue(outbox.sql.contains("pawbridge_community.outbox_events"));
        assertEquals(UUID.fromString((String)outbox.values.get(1)).toString(), outbox.values.get(1));
        assertEquals("Post", outbox.values.get(2));
        assertEquals("42", outbox.values.get(3));
        assertEquals("POST_DELETED", outbox.values.get(4));
        assertEquals(mapper.readTree("{\"postId\":42}"), mapper.readTree((String)outbox.values.get(5)));
        assertEquals("UTC", outbox.calendars.get(6).getTimeZone().getID());
        assertTrue(calls.get(3).sql.contains("DELETE FROM pawbridge_community.post_search_documents WHERE post_id = ?"));
        assertEquals(Map.of(1,42L), calls.get(3).values);
    }

    @Test
    void given__missing_or_already_deleted_locked_row__when__delete__then__no_mutation_is_attempted() throws Exception {
        when(result.next()).thenReturn(false);
        assertThrows(PostNotFoundException.class, () -> deletion().delete(42L, 7L));
        assertEquals(1, calls.size());
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
    }

    @Test
    void given__different_locked_owner__when__delete__then__no_mutation_is_attempted() throws Exception {
        when(result.getLong("author_id")).thenReturn(8L);
        assertThrows(PostNotOwnedException.class, () -> deletion().delete(42L, 7L));
        assertEquals(1, calls.size());
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
    }

    @Test
    void given__conditional_update_affects_no_row__when__delete__then__no_event_or_search_mutation_follows() {
        updateCount = 0;
        assertThrows(PostNotFoundException.class, () -> deletion().delete(42L, 7L));
        assertEquals(2, calls.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1,2,3,4})
    void given__lock_or_mutation_failure__when__delete__then__original_failure_leaves_transaction_without_retry(int failingStatement) {
        failAt = failingStatement;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> deletion().delete(42L, 7L)));
        assertFalse(transactionActive);
        assertEquals(failingStatement, calls.size());
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    @Test
    void given__commit_outcome_unknown__when__delete__then__original_transaction_failure_is_not_retried() {
        var unknown = new TransactionSystemException("synthetic commit outcome unknown");
        doAnswer(call -> {
            TransactionCallback<Void> callback = call.getArgument(0);
            transactionActive = true;
            try {
                callback.doInTransaction(new SimpleTransactionStatus());
                // Model a transaction API reporting an unknown outcome after the SQL callback.
                throw unknown;
            } finally {
                transactionActive = false;
            }
        }).when(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        assertSame(unknown, assertThrows(TransactionSystemException.class, () -> deletion().delete(42L, 7L)));
        assertEquals(4, calls.size());
        assertFalse(transactionActive);
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    private JdbcPostDeletion deletion() { return new JdbcPostDeletion(jdbc, transactions, mapper, clock); }
    private static final class StatementCall {
        private final String sql;
        private final Map<Integer,Object> values = new LinkedHashMap<>();
        private final Map<Integer,Calendar> calendars = new LinkedHashMap<>();
        private StatementCall(String sql) { this.sql = sql; }
    }
}
