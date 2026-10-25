package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = ActiveTrackingTransactions()
    private val row = mockk<ResultSet>()
    private val reader = JdbcCommentReader(jdbc, transactions)
    private val createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000)
    private val updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000)

    @Test
    fun `삭제되지 않은 댓글 번호일 때__번호로 조회하면__트랜잭션이 끝나기 전에 활성 댓글만 스냅샷으로 매핑한다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val mapper = secondArg<RowMapper<CommentSnapshot>>()
            listOf(mapper.mapRow(row, 0)!!)
        }
        every { row.getLong("comment_id") } returns 13L
        every { row.getLong("post_id") } returns 42L
        every { row.getLong("author_id") } returns 7L
        every { row.getString("content") } returns "댓글 본문"
        every { row.getTimestamp("created_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        every { row.getTimestamp("updated_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(updatedAt)
        }

        // when
        val result = reader.findById(13L)

        // then
        assertEquals(Optional.of(CommentSnapshot(13L, 42L, 7L, "댓글 본문", createdAt, updatedAt)), result)
        assertFalse(transactions.active)
        val sql = slot<String>()
        verify(exactly = 1) { jdbc.query(capture(sql), any<RowMapper<CommentSnapshot>>(), 13L) }
        assertEquals(
            "SELECT comment_id, post_id, author_id, content, created_at, updated_at " +
                "FROM pawbridge_community.comments WHERE comment_id = ? AND deleted_at IS NULL",
            sql.captured.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `삭제되지 않은 댓글 행이 없을 때__번호로 조회하면__트랜잭션이 끝난 뒤 빈 Optional을 돌려준다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } returns listOf()

        // when
        val result = reader.findById(13L)

        // then
        assertEquals(Optional.empty<CommentSnapshot>(), result)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 13L) }
    }

    @Test
    fun `단건 댓글 조회기를 쓸 때__수정하면__읽기 트랜잭션이 끝난 뒤 닉네임 조회와 수정을 시작한다`() {
        // given
        val calls = ArrayList<String>()
        val nicknames = object : UserNicknameQuery {
            override fun findNickname(userId: Long): Optional<String> {
                assertFalse(transactions.active)
                assertEquals(7L, userId)
                calls.add("nickname")
                return Optional.of("작성자")
            }
        }
        val modification = object : CommentModification {
            override fun update(commentId: Long, content: String?) {
                assertFalse(transactions.active)
                assertEquals(13L, commentId)
                assertEquals("새 댓글", content)
                calls.add("modification")
            }
        }
        val update = UpdateComment(reader, nicknames, modification)

        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val mapper = secondArg<RowMapper<CommentSnapshot>>()
            listOf(mapper.mapRow(row, 0)!!)
        }
        every { row.getLong("comment_id") } returns 13L
        every { row.getLong("post_id") } returns 42L
        every { row.getLong("author_id") } returns 7L
        every { row.getString("content") } returns "댓글 본문"
        every { row.getTimestamp("created_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        every { row.getTimestamp("updated_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(updatedAt)
        }

        // when
        val result = update.update(13L, 7L, "새 댓글")

        // then
        assertEquals(updatedAt, result.updatedAt)
        assertEquals(listOf("nickname", "modification"), calls)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 13L) }
    }

    @Test
    fun `단건 댓글 쿼리가 실패할 때__번호로 조회하면__트랜잭션이 끝난 뒤 원래 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("unavailable")

        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { reader.findById(13L) }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 13L) }
    }

    @Test
    fun `게시글에 댓글 행이 있을 때__게시글로 조회하면__트랜잭션이 끝나기 전에 모든 필드를 매핑한다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val mapper = secondArg<RowMapper<CommentSnapshot>>()
            listOf(mapper.mapRow(row, 0)!!)
        }
        every { row.getLong("comment_id") } returns 13L
        every { row.getLong("post_id") } returns 42L
        every { row.getLong("author_id") } returns 7L
        every { row.getString("content") } returns "댓글 본문"
        every { row.getTimestamp("created_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        every { row.getTimestamp("updated_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(updatedAt)
        }

        // when
        val result = reader.findByPostId(42L)

        // then
        assertEquals(listOf(CommentSnapshot(13L, 42L, 7L, "댓글 본문", createdAt, updatedAt)), result)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 42L) }
    }

    @Test
    fun `댓글 조회기를 쓸 때__닉네임을 조회하면__매핑 트랜잭션이 이미 끝나 있다`() {
        // given
        val nicknames = object : UserNicknameQuery {
            override fun findNickname(userId: Long): Optional<String> {
                assertFalse(transactions.active)
                assertEquals(7L, userId)
                return Optional.of("반려친구")
            }
        }
        val listComments = ListComments(reader, nicknames)

        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } answers {
            assertTrue(transactions.active)
            val mapper = secondArg<RowMapper<CommentSnapshot>>()
            listOf(mapper.mapRow(row, 0)!!)
        }
        every { row.getLong("comment_id") } returns 13L
        every { row.getLong("post_id") } returns 42L
        every { row.getLong("author_id") } returns 7L
        every { row.getString("content") } returns "댓글 본문"
        every { row.getTimestamp("created_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(createdAt)
        }
        every { row.getTimestamp("updated_at", any<Calendar>()) } answers {
            assertEquals("UTC", secondArg<Calendar>().timeZone.id)
            Timestamp.valueOf(updatedAt)
        }

        // when
        val result = listComments.read(42L)

        // then
        assertEquals("반려친구", result.first().authorNickname)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 42L) }
    }

    @Test
    fun `게시글에 댓글 행이 없을 때__게시글로 조회하면__트랜잭션이 끝난 뒤 빈 목록을 돌려준다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } returns listOf()

        // when
        val result = reader.findByPostId(42L)

        // then
        assertEquals(listOf<CommentSnapshot>(), result)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 42L) }
    }

    @Test
    fun `게시글 댓글 쿼리가 실패할 때__게시글로 조회하면__트랜잭션이 끝난 뒤 원래 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("unavailable")

        every { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), *anyVararg()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { reader.findByPostId(42L) }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<CommentSnapshot>>(), 42L) }
    }

    /** An in-memory TransactionOperations that only records whether a callback is running. */
    private class ActiveTrackingTransactions : TransactionOperations {
        var active = false

        override fun <T> execute(action: TransactionCallback<T>): T? {
            active = true
            try {
                return action.doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
    }
}
