package com.pawbridge.backend.community.internal.comment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcCommentDeletionTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant DELETED = Instant.parse("2026-10-19T03:30:10.123456789Z");
    private static final Instant UPDATED = Instant.parse("2026-10-19T03:30:11.654321987Z");
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final Connection connection = mock(Connection.class);
    private final ResultSet result = mock(ResultSet.class);
    private final Clock clock = mock(Clock.class);
    private final JdbcCommentDeletion deletion = new JdbcCommentDeletion(jdbc, transactions, clock);
    private final List<StatementCall> calls = new ArrayList<>();
    private final RuntimeException failure = new DataAccessResourceFailureException("synthetic SQL failure");
    private boolean transactionActive;
    private int failAt;
    private int updateCount = 1;
    private boolean failTimestampBinding;

    @BeforeEach
    void prepareTransactionAndJdbc() throws Exception {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenReturn(DELETED, UPDATED);
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Void>>any())).thenAnswer(invocation -> {
            transactionActive = true;
            try {
                return invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(new SimpleTransactionStatus());
            } finally { transactionActive = false; }
        });
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            assertTrue(transactionActive);
            PreparedStatement statement = mock(PreparedStatement.class);
            StatementCall call = new StatementCall(invocation.getArgument(0), statement);
            calls.add(call);
            doAnswer(binding -> {
                call.values.put(binding.getArgument(0), binding.getArgument(1));
                return null;
            }).when(statement).setObject(anyInt(), any());
            doAnswer(binding -> {
                call.values.put(binding.getArgument(0), binding.getArgument(1));
                call.calendars.put(binding.getArgument(0), binding.getArgument(2));
                return null;
            }).when(statement).setTimestamp(anyInt(), any(Timestamp.class), any(Calendar.class));
            if (failTimestampBinding && calls.size() == 2) {
                doThrow(failure).when(statement).setTimestamp(eq(1), any(Timestamp.class), any(Calendar.class));
            }
            return statement;
        });
        when(result.next()).thenReturn(true);
        when(result.getLong("author_id")).thenReturn(7L);
        when(jdbc.query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
                    if (failAt == calls.size()) throw failure;
                    return invocation.getArgument(1, ResultSetExtractor.class).extractData(result);
                });
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            if (failAt == calls.size()) throw failure;
            return updateCount;
        });
    }

    @Test
    void given__own_active_comment__when__delete__then__ordinary_lookup_and_two_distinct_dates_use_one_transaction()
            throws Exception {
        deletion.delete(42L, 7L);

        assertFalse(transactionActive);
        assertEquals(2, calls.size());
        StatementCall lookup = calls.getFirst();
        String lookupSql = lookup.sql.replaceAll("\\s+", " ").strip();
        assertEquals("SELECT author_id FROM pawbridge_community.comments WHERE comment_id = ? AND deleted_at IS NULL", lookupSql);
        assertEquals(Map.of(1, 42L), lookup.values);
        StatementCall update = calls.getLast();
        String updateSql = update.sql.replaceAll("\\s+", " ").strip();
        assertEquals("UPDATE pawbridge_community.comments SET deleted_at = ?, updated_at = ? WHERE comment_id = ?", updateSql);
        assertEquals(Map.of(1, Timestamp.valueOf(LocalDateTime.ofInstant(DELETED, ZONE)),
                2, Timestamp.valueOf(LocalDateTime.ofInstant(UPDATED, ZONE)), 3, 42L), update.values);
        assertEquals("UTC", update.calendars.get(1).getTimeZone().getID());
        assertEquals("UTC", update.calendars.get(2).getTimeZone().getID());
        verify(clock, times(2)).instant();
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        verify(jdbc).query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any());
        verify(jdbc).update(any(PreparedStatementCreator.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__missing_or_deleted_comment__when__delete_by_another_author__then__not_found_precedes_ownership_and_update()
            throws Exception {
        when(result.next()).thenReturn(false);

        CommentNotFoundException rejected = assertThrows(CommentNotFoundException.class, () -> deletion.delete(42L, 99L));
        assertEquals("댓글을 찾을 수 없습니다.", rejected.getMessage());
        assertEquals(1, calls.size());
        assertFalse(transactionActive);
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
        verifyNoInteractions(clock);
    }

    @Test
    void given__different_author__when__delete__then__ownership_rejection_precedes_clock_and_update() throws Exception {
        when(result.getLong("author_id")).thenReturn(8L);

        assertThrows(CommentNotOwnedException.class, () -> deletion.delete(42L, 7L));
        assertEquals(1, calls.size());
        assertFalse(transactionActive);
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
        verifyNoInteractions(clock);
    }

    @Test
    void given__row_disappears_after_lookup__when__delete__then__unexpected_failure_is_not_not_found_or_retried() {
        updateCount = 0;

        assertThrows(IllegalStateException.class, () -> deletion.delete(42L, 7L));
        assertEquals(2, calls.size());
        assertFalse(transactionActive);
        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class));
        verify(transactions, times(1)).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void given__lookup_or_update_failure__when__delete__then__original_failure_leaves_transaction_without_retry(int statement) {
        failAt = statement;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> deletion.delete(42L, 7L)));
        assertEquals(statement, calls.size());
        assertFalse(transactionActive);
        verify(transactions, times(1)).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    void given__commit_failure_after_update__when__delete__then__only_named_charset_error_is_translated(
            TransactionSystemException commitFailure, boolean translated) {
        failAfterCallback(commitFailure);

        if (translated) {
            UnsupportedCommentCharactersException rejected = assertThrows(UnsupportedCommentCharactersException.class,
                    () -> deletion.delete(42L, 7L));
            assertSame(commitFailure, rejected.getCause());
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", rejected.getMessage());
        } else {
            assertSame(commitFailure, assertThrows(TransactionSystemException.class, () -> deletion.delete(42L, 7L)));
        }
        assertEquals(2, calls.size());
        assertFalse(transactionActive);
        verify(transactions, times(1)).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    @Test
    void given__timestamp_binding_failure__when__preparing_update__then__close_statement_before_propagating_failure()
            throws Exception {
        failTimestampBinding = true;

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> deletion.delete(42L, 7L)));
        assertEquals(2, calls.size());
        verify(calls.getLast().statement).close();
        assertFalse(transactionActive);
    }

    private void failAfterCallback(RuntimeException commitFailure) {
        doAnswer(invocation -> {
            transactionActive = true;
            try {
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(new SimpleTransactionStatus());
                throw commitFailure;
            } finally { transactionActive = false; }
        }).when(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
    }

    private static Stream<Arguments> commitFailures() {
        return Stream.of(
                Arguments.of(new TransactionSystemException("commit", constraint("ck_rollback_charset_comments")), true),
                Arguments.of(new TransactionSystemException("commit", constraint("business_constraint")), false),
                Arguments.of(new TransactionSystemException("commit outcome unknown"), false));
    }

    private static PSQLException constraint(String name) {
        return new PSQLException(new ServerErrorMessage(
                "SERROR\0C23514\0Msynthetic\0spawbridge_community\0n" + name + "\0"));
    }

    private static final class StatementCall {
        private final String sql;
        private final PreparedStatement statement;
        private final Map<Integer, Object> values = new LinkedHashMap<>();
        private final Map<Integer, Calendar> calendars = new LinkedHashMap<>();

        private StatementCall(String sql, PreparedStatement statement) {
            this.sql = sql;
            this.statement = statement;
        }
    }
}
