package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import com.pawbridge.backend.user.api.UserNicknameQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Optional

class JdbcCommentReaderTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val row = mock(ResultSet::class.java)
    private val reader = JdbcCommentReader(jdbc, transactions)
    private val createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000)
    private val updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000)
    private var transactionActive = false

    @BeforeEach
    fun prepareTransactionAndRow() {
        whenever(transactions.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            transactionActive = true
            try {
                callback.doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(jdbc.query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(42L))).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val mapper = invocation.getArgument<RowMapper<CommentSnapshot>>(1)
            listOf(mapper.mapRow(row, 0))
        }
        whenever(jdbc.query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(13L))).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val mapper = invocation.getArgument<RowMapper<CommentSnapshot>>(1)
            listOf(mapper.mapRow(row, 0))
        }
        whenever(row.getLong("comment_id")).thenReturn(13L)
        whenever(row.getLong("post_id")).thenReturn(42L)
        whenever(row.getLong("author_id")).thenReturn(7L)
        whenever(row.getString("content")).thenReturn("댓글 본문")
        whenever(row.getTimestamp(eq("created_at"), any(Calendar::class.java))).thenAnswer { invocation ->
            assertEquals("UTC", invocation.getArgument<Calendar>(1).timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        whenever(row.getTimestamp(eq("updated_at"), any(Calendar::class.java))).thenAnswer { invocation ->
            assertEquals("UTC", invocation.getArgument<Calendar>(1).timeZone.id)
            Timestamp.valueOf(updatedAt)
        }
    }

    @Test
    fun given__active_comment_id__when__find_by_id__then__active_only_snapshot_is_mapped_before_transaction_returns() {
        assertEquals(
            Optional.of(CommentSnapshot(13L, 42L, 7L, "댓글 본문", createdAt, updatedAt)),
            reader.findById(13L),
        )
        assertFalse(transactionActive)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).query(sql.capture(), any<RowMapper<CommentSnapshot>>(), eq(13L))
        assertEquals(
            "SELECT comment_id, post_id, author_id, content, created_at, updated_at " +
                "FROM pawbridge_community.comments WHERE comment_id = ? AND deleted_at IS NULL",
            sql.value.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun given__no_active_comment_row__when__find_by_id__then__empty_optional_is_returned_after_transaction() {
        doReturn(listOf<CommentSnapshot>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(13L))

        assertEquals(Optional.empty<CommentSnapshot>(), reader.findById(13L))
        assertFalse(transactionActive)
    }

    @Test
    fun given__single_comment_reader__when__updating__then__nickname_and_modification_start_after_read_transaction_returns() {
        val calls = ArrayList<String>()
        val nicknames = object : UserNicknameQuery {
            override fun findNickname(userId: Long): Optional<String> {
                assertFalse(transactionActive)
                assertEquals(7L, userId)
                calls.add("nickname")
                return Optional.of("작성자")
            }
        }
        val modification = object : CommentModification {
            override fun update(commentId: Long, content: String?) {
                assertFalse(transactionActive)
                assertEquals(13L, commentId)
                assertEquals("새 댓글", content)
                calls.add("modification")
            }
        }
        val update = UpdateComment(reader, nicknames, modification)

        assertEquals(updatedAt, update.update(13L, 7L, "새 댓글").updatedAt)
        assertEquals(listOf("nickname", "modification"), calls)
        assertFalse(transactionActive)
    }

    @Test
    fun given__single_comment_query_failure__when__find_by_id__then__original_failure_propagates_after_transaction() {
        val failure = DataAccessResourceFailureException("unavailable")
        doThrow(failure).`when`(jdbc)
            .query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(13L))

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { reader.findById(13L) },
        )
        assertFalse(transactionActive)
    }

    @Test
    fun given__comment_rows__when__find_by_post__then__all_fields_are_mapped_before_transaction_returns() {
        assertEquals(
            listOf(CommentSnapshot(13L, 42L, 7L, "댓글 본문", createdAt, updatedAt)),
            reader.findByPostId(42L),
        )
        assertFalse(transactionActive)
    }

    @Test
    fun given__comment_reader__when__nickname_is_queried__then__mapping_transaction_has_completed() {
        val nicknames = object : UserNicknameQuery {
            override fun findNickname(userId: Long): Optional<String> {
                assertFalse(transactionActive)
                assertEquals(7L, userId)
                return Optional.of("반려친구")
            }
        }
        val listComments = ListComments(reader, nicknames)

        assertEquals("반려친구", listComments.read(42L).first().authorNickname)
        assertFalse(transactionActive)
    }

    @Test
    fun given__no_comment_rows__when__find_by_post__then__empty_list_is_returned_after_transaction() {
        doReturn(listOf<CommentSnapshot>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(42L))

        assertEquals(listOf<CommentSnapshot>(), reader.findByPostId(42L))
        assertFalse(transactionActive)
    }

    @Test
    fun given__query_failure__when__find_by_post__then__original_failure_propagates_after_transaction() {
        val failure = DataAccessResourceFailureException("unavailable")
        doThrow(failure).`when`(jdbc)
            .query(anyString(), any<RowMapper<CommentSnapshot>>(), eq(42L))

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { reader.findByPostId(42L) },
        )
        assertFalse(transactionActive)
    }
}
