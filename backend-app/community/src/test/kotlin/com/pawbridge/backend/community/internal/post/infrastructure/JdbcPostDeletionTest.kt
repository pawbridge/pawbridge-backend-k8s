package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.TransactionSystemException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class JdbcPostDeletionTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = FakeTransactions()
    private val calls = ArrayList<StatementCall>()
    private val connection = recordingConnection(calls) { assertTrue(transactions.active) }
    private val row = FakeRow()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T00:05:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private var failAt = 0
    private var updateCount = 1
    private val failure: RuntimeException = IllegalStateException("synthetic DB failure")

    @Test
    fun `본인의 활성 게시글일 때__삭제하면__잠금과 두 변경이 아웃박스 없이 한 트랜잭션을 공유한다`() {
        // given
        row.longs["author_id"] = 7L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            updateCount
        }

        // when
        deletion().delete(42L, 7L)

        // then
        assertFalse(transactions.active)
        assertEquals(3, calls.size)
        assertTrue(calls.stream().noneMatch { call -> call.sql.contains("outbox_events") })
        val lock = calls[0]
        assertTrue(lock.sql.contains("pawbridge_community.posts"))
        assertTrue(lock.sql.contains("deleted_at IS NULL"))
        assertTrue(lock.sql.contains("FOR UPDATE"))
        assertEquals(42L, lock.values[1])
        val source = calls[1]
        assertTrue(source.sql.contains("SET deleted_at = ?, updated_at = ?"))
        assertTrue(source.sql.contains("post_id = ? AND author_id = ? AND deleted_at IS NULL"))
        assertFalse(source.sql.contains("title ="))
        assertFalse(source.sql.contains("image_urls ="))
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values[1])
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values[2])
        assertEquals(42L, source.values[3])
        assertEquals(7L, source.values[4])
        assertEquals("UTC", source.calendars[1]!!.timeZone.id)
        assertEquals("UTC", source.calendars[2]!!.timeZone.id)
        assertTrue(calls[2].sql.contains("DELETE FROM pawbridge_community.post_search_documents WHERE post_id = ?"))
        assertEquals(mapOf(1 to 42L), calls[2].values)
    }

    @Test
    fun `잠근 행이 없거나 이미 삭제됐을 때__삭제하면__어떤 변경도 시도하지 않는다`() {
        // given
        row.hasNext = false

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }

        // when
        val thrown = assertThrows(PostNotFoundException::class.java) { deletion().delete(42L, 7L) }

        // then
        assertEquals(PostNotFoundException::class.java, thrown.javaClass)
        assertEquals(1, calls.size)
        verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
    }

    @Test
    fun `잠근 소유자가 다를 때__삭제하면__어떤 변경도 시도하지 않는다`() {
        // given
        row.longs["author_id"] = 8L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }

        // when
        val thrown = assertThrows(PostNotOwnedException::class.java) { deletion().delete(42L, 7L) }

        // then
        assertEquals(PostNotOwnedException::class.java, thrown.javaClass)
        assertEquals(1, calls.size)
        verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
    }

    @Test
    fun `조건부 갱신이 한 행도 바꾸지 못할 때__삭제하면__검색 문서 변경이 뒤따르지 않는다`() {
        // given
        updateCount = 0
        row.longs["author_id"] = 7L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }

        // when
        val thrown = assertThrows(PostNotFoundException::class.java) { deletion().delete(42L, 7L) }

        // then
        assertEquals(PostNotFoundException::class.java, thrown.javaClass)
        assertEquals(2, calls.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `잠금이나 변경이 실패할 때__삭제하면__재시도 없이 원래 예외로 트랜잭션을 벗어난다`(
        failingStatement: Int,
    ) {
        // given
        failAt = failingStatement
        row.longs["author_id"] = 7L

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            updateCount
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { deletion().delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
        assertEquals(failingStatement, calls.size)
        assertEquals(1, transactions.executions)
    }

    @Test
    fun `커밋 결과를 알 수 없을 때__삭제하면__원래 트랜잭션 예외를 재시도하지 않고 던진다`() {
        // given
        val unknown = TransactionSystemException("synthetic commit outcome unknown")
        row.longs["author_id"] = 7L
        // Model a transaction API reporting an unknown outcome after the SQL callback.
        transactions.failureAfterCallback = unknown

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(row.resultSet)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { deletion().delete(42L, 7L) }

        // then
        assertSame(unknown, thrown)
        assertEquals(3, calls.size)
        assertFalse(transactions.active)
        assertEquals(1, transactions.executions)
    }

    private fun deletion(): JdbcPostDeletion = JdbcPostDeletion(jdbc, transactions, clock)
}
