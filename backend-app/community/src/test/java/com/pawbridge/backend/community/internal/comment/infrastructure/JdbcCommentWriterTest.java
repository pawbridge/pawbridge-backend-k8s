package com.pawbridge.backend.community.internal.comment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class JdbcCommentWriterTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"));
    private final JdbcCommentWriter writer = new JdbcCommentWriter(jdbc, transactions, clock);
    private boolean transactionActive;
    private int insertAttempts;

    @BeforeEach
    void prepareTransactionAndJdbc() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<CommentSnapshot>>any()))
                .thenAnswer(invocation -> {
                    transactionActive = true;
                    try {
                        return invocation.getArgument(0, TransactionCallback.class)
                                .doInTransaction(new SimpleTransactionStatus());
                    } finally {
                        transactionActive = false;
                    }
                });
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(result.next()).thenReturn(true);
        when(result.getLong("comment_id")).thenReturn(13L);
        when(jdbc.query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any()))
                .thenAnswer(invocation -> {
                    assertTrue(transactionActive);
                    insertAttempts++;
                    invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
                    return invocation.getArgument(1, ResultSetExtractor.class).extractData(result);
                });
    }

    @Test
    void given__comment_content_and_clock__when__create__then__one_comment_insert_returns_identity_and_full_precision_snapshot()
            throws Exception {
        String content = "  댓글 원문\n ";
        LocalDateTime now = LocalDateTime.now(clock);

        assertEquals(new CommentSnapshot(13L, 42L, 7L, content, now, now), writer.create(42L, 7L, content));
        assertFalse(transactionActive);
        assertEquals(1, insertAttempts);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("INSERT INTO pawbridge_community.comments"));
        assertTrue(sql.getValue().contains("RETURNING comment_id"));
        verify(statement).setLong(1, 42L);
        verify(statement).setLong(2, 7L);
        verify(statement).setString(3, content);
        ArgumentCaptor<Timestamp> timestamps = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Calendar> calendars = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(4), timestamps.capture(), calendars.capture());
        verify(statement).setTimestamp(eq(5), timestamps.capture(), calendars.capture());
        assertEquals(Timestamp.valueOf(now), timestamps.getAllValues().get(0));
        assertEquals(Timestamp.valueOf(now), timestamps.getAllValues().get(1));
        assertEquals(123456789, timestamps.getAllValues().get(0).getNanos());
        assertEquals("UTC", calendars.getAllValues().get(0).getTimeZone().getID());
        assertSame(calendars.getAllValues().get(0), calendars.getAllValues().get(1));
        verify(jdbc).query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any());
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__missing_generated_identity__when__create__then__failure_leaves_transaction_without_retry() throws Exception {
        when(result.next()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> writer.create(42L, 7L, "댓글"));
        assertEquals(1, insertAttempts);
        assertFalse(transactionActive);
    }

    @Test
    void given__insert_failure__when__create__then__original_failure_propagates_after_one_attempt_and_transaction_exit() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("synthetic unavailable");
        doThrow(failure).when(jdbc)
                .query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any());

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> writer.create(42L, 7L, "댓글")));
        assertFalse(transactionActive);
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any());
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__charset_failure_at_commit__when__create__then__named_community_constraint_becomes_user_error() {
        TransactionSystemException failure = new TransactionSystemException("commit",
                constraint("23514", "pawbridge_community", "ck_rollback_charset_comments"));
        failAfterCallback(failure);

        UnsupportedCommentCharactersException translated = assertThrows(UnsupportedCommentCharactersException.class,
                () -> writer.create(42L, 7L, "댓글"));
        assertSame(failure, translated.getCause());
        assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.getMessage());
        assertEquals(1, insertAttempts);
        assertFalse(transactionActive);
    }

    @Test
    void given__unrelated_check_failure_at_commit__when__create__then__original_failure_is_not_a_user_error() {
        TransactionSystemException failure = new TransactionSystemException("commit",
                constraint("23514", "pawbridge_community", "business_constraint"));
        failAfterCallback(failure);

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> writer.create(42L, 7L, "댓글")));
        assertEquals(1, insertAttempts);
        assertFalse(transactionActive);
    }

    @Test
    void given__timestamp_binding_failure__when__preparing_insert__then__statement_is_closed_and_failure_propagates()
            throws Exception {
        IllegalStateException failure = new IllegalStateException("synthetic binding failure");
        doThrow(failure).when(statement).setTimestamp(eq(4), any(Timestamp.class), any(Calendar.class));

        assertSame(failure, assertThrows(IllegalStateException.class, () -> writer.create(42L, 7L, "댓글")));
        verify(statement).close();
        assertEquals(1, insertAttempts);
        assertFalse(transactionActive);
    }

    private void failAfterCallback(RuntimeException failure) {
        doAnswer(invocation -> {
            transactionActive = true;
            try {
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(new SimpleTransactionStatus());
                throw failure;
            } finally {
                transactionActive = false;
            }
        }).when(transactions).execute(ArgumentMatchers.<TransactionCallback<CommentSnapshot>>any());
    }

    private PSQLException constraint(String state, String schema, String name) {
        return new PSQLException(new ServerErrorMessage(
                "SERROR\0C" + state + "\0Msynthetic\0s" + schema + "\0n" + name + "\0"));
    }
}
