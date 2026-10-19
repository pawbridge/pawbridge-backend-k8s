package com.pawbridge.backend.community.internal.comment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcCommentReaderTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final ResultSet row = mock(ResultSet.class);
    private final JdbcCommentReader reader = new JdbcCommentReader(jdbc, transactions);
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000);
    private final LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000);
    private boolean transactionActive;

    @BeforeEach
    void prepareTransactionAndRow() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<Object> callback = invocation.getArgument(0);
                    transactionActive = true;
                    try {
                        return callback.doInTransaction(new SimpleTransactionStatus());
                    } finally {
                        transactionActive = false;
                    }
                });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<CommentSnapshot>>any(), eq(42L)))
                .thenAnswer(invocation -> {
                    assertTrue(transactionActive);
                    RowMapper<CommentSnapshot> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(row.getLong("comment_id")).thenReturn(13L);
        when(row.getLong("post_id")).thenReturn(42L);
        when(row.getLong("author_id")).thenReturn(7L);
        when(row.getString("content")).thenReturn("댓글 본문");
        when(row.getTimestamp(eq("created_at"), any(Calendar.class))).thenAnswer(invocation -> {
            assertEquals("UTC", invocation.getArgument(1, Calendar.class).getTimeZone().getID());
            return Timestamp.valueOf(createdAt);
        });
        when(row.getTimestamp(eq("updated_at"), any(Calendar.class))).thenAnswer(invocation -> {
            assertEquals("UTC", invocation.getArgument(1, Calendar.class).getTimeZone().getID());
            return Timestamp.valueOf(updatedAt);
        });
    }

    @Test
    void given__comment_rows__when__find_by_post__then__all_fields_are_mapped_before_transaction_returns() {
        assertEquals(List.of(new CommentSnapshot(13L, 42L, 7L, "댓글 본문", createdAt, updatedAt)),
                reader.findByPostId(42L));
        assertFalse(transactionActive);
    }

    @Test
    void given__comment_reader__when__nickname_is_queried__then__mapping_transaction_has_completed() {
        ListComments listComments = new ListComments(reader, authorId -> {
            assertFalse(transactionActive);
            assertEquals(7L, authorId);
            return Optional.of("반려친구");
        });

        assertEquals("반려친구", listComments.read(42L).getFirst().authorNickname());
        assertFalse(transactionActive);
    }

    @Test
    void given__no_comment_rows__when__find_by_post__then__empty_list_is_returned_after_transaction() {
        doReturn(List.of()).when(jdbc)
                .query(anyString(), ArgumentMatchers.<RowMapper<CommentSnapshot>>any(), eq(42L));

        assertEquals(List.of(), reader.findByPostId(42L));
        assertFalse(transactionActive);
    }

    @Test
    void given__query_failure__when__find_by_post__then__original_failure_propagates_after_transaction() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        doThrow(failure).when(jdbc)
                .query(anyString(), ArgumentMatchers.<RowMapper<CommentSnapshot>>any(), eq(42L));

        assertSame(failure,
                assertThrows(DataAccessResourceFailureException.class, () -> reader.findByPostId(42L)));
        assertFalse(transactionActive);
    }
}
