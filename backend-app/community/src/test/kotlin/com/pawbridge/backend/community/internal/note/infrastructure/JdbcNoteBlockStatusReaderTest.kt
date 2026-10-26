package com.pawbridge.backend.community.internal.note.infrastructure

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException

class JdbcNoteBlockStatusReaderTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 100])
    fun `작성자 ID가 상한 안에 있을 때__차단된 작성자를 읽으면__소유자와 각 ID를 바인딩하고 읽기 전용 트랜잭션 안에서 원시 long으로 매핑한다`(
        size: Int,
    ) {
        // given
        val fixture = Fixture()
        val ids = (0L until size.toLong()).map { index -> if (index == 0L) Long.MAX_VALUE else 1000 - index }
        fixture.jdbc.rows = listOf(Long.MAX_VALUE)

        // when
        val authors = fixture.reader.blockedAuthors(7L, ids)

        // then
        assertEquals(listOf(Long.MAX_VALUE), authors)
        val parameters = arrayOfNulls<Any?>(size + 1)
        parameters[0] = 7L
        for (index in 0 until size) {
            parameters[index + 1] = ids[index]
        }
        assertArrayEquals(parameters, fixture.jdbc.arguments)
        assertEquals(size + 1L, fixture.jdbc.sql!!.count { character -> character == '?' }.toLong())
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `나가는 차단과 일치하는 행이 없을 때__차단된 작성자를 읽으면__질의와 커밋이 성공한 뒤 빈 결과를 돌려준다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf()

        // when
        val authors = fixture.reader.blockedAuthors(7L, listOf(8L))

        // then
        assertEquals(listOf<Long>(), authors)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `차단 질의 또는 트랜잭션이 실패했다가 복구될 때__차단된 작성자를 읽으면__빈 결과로 대체하거나 감싸지 않고 같은 예외를 전파한다`(
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY_DAO -> DataAccessResourceFailureException("synthetic-private-block-sql")
            FailurePhase.QUERY_RUNTIME -> IllegalStateException("synthetic-private-block-runtime")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY_DAO, FailurePhase.QUERY_RUNTIME -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.reader.blockedAuthors(7L, listOf(8L)) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        // when
        val recovered = fixture.reader.blockedAuthors(7L, listOf(8L))

        // then
        assertEquals(listOf(8L), recovered)
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val reader: JdbcNoteBlockStatusReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcNoteBlockStatusReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var rows: List<Long> = listOf(8L)
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            assertTrue(transactions.active)
            this.sql = sql
            this.arguments = arguments
            queries++
            if (failure != null) {
                throw failure!!
            }
            val result = ArrayList<T>()
            for (index in rows.indices) {
                val row = mockk<ResultSet>()
                try {
                    every { row.getLong("blocked_id") } returns rows[index]
                    result.add(rowMapper.mapRow(row, index) as T)
                } catch (exception: SQLException) {
                    throw AssertionError(exception)
                }
            }
            return result
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
        }
    }
}
