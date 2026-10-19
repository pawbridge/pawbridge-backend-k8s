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

import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcCommentModificationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant UPDATED = Instant.parse("2026-10-19T03:30:10.123456789Z");
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final Clock clock = mock(Clock.class);
    private final JdbcCommentWriter writer = new JdbcCommentWriter(jdbc, transactions, clock);
    private boolean transactionActive;
    private int updateCount = 1;
    private int updateAttempts;

    @BeforeEach
    void prepareTransactionAndJdbc() throws Exception {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenAnswer(invocation -> {
            assertTrue(transactionActive);
            return UPDATED;
        });
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Void>>any())).thenAnswer(invocation -> {
            transactionActive = true;
            try {
                return invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(new SimpleTransactionStatus());
            } finally { transactionActive = false; }
        });
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            assertTrue(transactionActive);
            return statement;
        });
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(invocation -> {
            updateAttempts++;
            invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            return updateCount;
        });
    }

    @Test
    void given__changed_raw_content__when__update__then__one_id_only_update_binds_content_and_audit_time_without_reads()
            throws Exception {
        String content = "  수정 원문\n\u3000 ";

        writer.update(13L, content);

        assertFalse(transactionActive);
        assertEquals(1, updateAttempts);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertEquals("UPDATE pawbridge_community.comments SET content = ?, updated_at = ? WHERE comment_id = ?",
                sql.getValue().replaceAll("\\s+", " ").strip());
        verify(statement).setString(1, content);
        ArgumentCaptor<Timestamp> timestamp = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Calendar> calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(2), timestamp.capture(), calendar.capture());
        assertEquals(Timestamp.valueOf(LocalDateTime.ofInstant(UPDATED, ZONE)), timestamp.getValue());
        assertEquals(123456789, timestamp.getValue().getNanos());
        assertEquals("UTC", calendar.getValue().getTimeZone().getID());
        verify(statement).setLong(3, 13L);
        verify(clock).instant();
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        verify(jdbc).update(any(PreparedStatementCreator.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__row_disappeared_after_snapshot__when__update__then__zero_rows_is_unexpected_failure_without_retry() {
        updateCount = 0;

        assertThrows(IllegalStateException.class, () -> writer.update(13L, "댓글"));
        assertFalse(transactionActive);
        assertEquals(1, updateAttempts);
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        verify(jdbc).update(any(PreparedStatementCreator.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__update_sql_failure__when__update__then__original_failure_leaves_transaction_without_retry() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("synthetic unavailable");
        doThrow(failure).when(jdbc).update(any(PreparedStatementCreator.class));

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> writer.update(13L, "댓글")));
        assertFalse(transactionActive);
        verify(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        verify(jdbc).update(any(PreparedStatementCreator.class));
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    void given__commit_failure_after_update__when__update__then__only_named_community_charset_rejection_is_translated(
            TransactionSystemException commitFailure, boolean translated) {
        doAnswer(invocation -> {
            transactionActive = true;
            try {
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(new SimpleTransactionStatus());
                throw commitFailure;
            } finally { transactionActive = false; }
        }).when(transactions).execute(ArgumentMatchers.<TransactionCallback<Void>>any());

        if (translated) {
            UnsupportedCommentCharactersException failure = assertThrows(UnsupportedCommentCharactersException.class,
                    () -> writer.update(13L, "댓글"));
            assertSame(commitFailure, failure.getCause());
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", failure.getMessage());
        } else {
            assertSame(commitFailure, assertThrows(TransactionSystemException.class, () -> writer.update(13L, "댓글")));
        }
        assertFalse(transactionActive);
        assertEquals(1, updateAttempts);
        verify(transactions, times(1)).execute(ArgumentMatchers.<TransactionCallback<Void>>any());
        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void given__timestamp_binding_and_close_failures__when__preparing_update__then__statement_is_closed_and_original_failure_retained()
            throws Exception {
        IllegalStateException failure = new IllegalStateException("synthetic binding failure");
        SQLException closing = new SQLException("synthetic close failure");
        doThrow(failure).when(statement).setTimestamp(eq(2), any(Timestamp.class), any(Calendar.class));
        doThrow(closing).when(statement).close();

        assertSame(failure, assertThrows(IllegalStateException.class, () -> writer.update(13L, "댓글")));
        assertEquals(1, updateAttempts);
        assertFalse(transactionActive);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closing, failure.getSuppressed()[0]);
        verify(statement).close();
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
}
