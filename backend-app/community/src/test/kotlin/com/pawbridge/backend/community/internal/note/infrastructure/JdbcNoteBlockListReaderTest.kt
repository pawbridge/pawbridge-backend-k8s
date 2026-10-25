package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block
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
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import java.sql.Timestamp
import java.time.Instant
import java.util.stream.Stream

class JdbcNoteBlockListReaderTest {
    @Test
    fun `원시 long 개수와 시각 행이 있을 때__개수를 세고 목록을 읽으면__완료된 읽기 전용 트랜잭션 둘을 쓰고 소유자와 long 오프셋을 바인딩한다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(
            Block(Long.MAX_VALUE, Instant.parse("2026-10-19T14:59:59.123456Z")),
            Block(8L, Instant.parse("1969-12-31T23:59:59.000001Z")),
        )

        // when
        val counted = fixture.reader.count(7L)
        val activeAfterCount = fixture.transactions.active
        val blocks = fixture.reader.read(7L, 10000)

        // then
        assertEquals(5_000_000_000L, counted)
        assertFalse(activeAfterCount)
        assertEquals(fixture.jdbc.rows, blocks)
        assertEquals(listOf("begin", "count", "commit", "begin", "page", "commit"), fixture.events)
        assertEquals(2, fixture.jdbc.calls.size)
        val count = fixture.jdbc.calls[0]
        assertEquals(
            "SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ?",
            count.sql.trim(),
        )
        assertArrayEquals(arrayOf<Any?>(7L), count.arguments)
        val page = fixture.jdbc.calls[1]
        assertEquals(
            "SELECT blocked_id, created_at FROM pawbridge_community.private_note_blocks " +
                "WHERE member_id = ? ORDER BY created_at DESC, blocked_id DESC LIMIT 10 OFFSET ?",
            page.sql.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(7L, 100000L), page.arguments)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `나가는 차단 행이 없을 때__목록을 읽으면__질의와 커밋이 성공한 뒤에만 빈 목록을 돌려준다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf()

        // when
        val blocks = fixture.reader.read(7L, 0)

        // then
        assertEquals(listOf<Block>(), blocks)
        assertEquals(listOf("begin", "page", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("failureCases")
    fun `질의 또는 트랜잭션이 실패했다가 복구될 때__개수를 세거나 목록을 읽으면__0이나 빈 목록으로 대체하거나 감싸지 않고 같은 예외를 전파한다`(
        operation: Operation,
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("private block begin")
            FailurePhase.QUERY_DAO -> DataAccessResourceFailureException("private block SQL")
            FailurePhase.QUERY_RUNTIME -> IllegalStateException("private block runtime")
            FailurePhase.COMMIT -> TransactionSystemException("private block commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY_DAO, FailurePhase.QUERY_RUNTIME -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { invoke(fixture.reader, operation) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.calls.size)
        assertEquals(
            if (phase == FailurePhase.QUERY_DAO || phase == FailurePhase.QUERY_RUNTIME) 1 else 0,
            fixture.transactions.rollbacks,
        )
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        // when
        val recovered = invoke(fixture.reader, operation)

        // then
        assertEquals(
            if (operation == Operation.COUNT) java.lang.Long.valueOf(5_000_000_000L) else fixture.jdbc.rows,
            recovered,
        )
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    enum class Operation { COUNT, PAGE }

    enum class FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val reader: JdbcNoteBlockListReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcNoteBlockListReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var rows: List<Block> = listOf(Block(8L, Instant.EPOCH))
        var failure: RuntimeException? = null

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg arguments: Any?): T? {
            record("count", sql, arguments)
            return requiredType.cast(java.lang.Long.valueOf(5_000_000_000L))
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            record("page", sql, arguments)
            val result = ArrayList<T>()
            for (index in rows.indices) {
                val row = mockk<ResultSet>()
                try {
                    every { row.getLong("blocked_id") } returns rows[index].memberId
                    every { row.getTimestamp("created_at") } returns Timestamp.from(rows[index].createdAt)
                    result.add(rowMapper.mapRow(row, index) as T)
                } catch (exception: SQLException) {
                    throw AssertionError(exception)
                }
            }
            return result
        }

        private fun record(operation: String, sql: String, arguments: Array<out Any?>) {
            assertTrue(transactions.active)
            events.add(operation)
            calls.add(Call(sql, arguments))
            if (failure != null) {
                throw failure!!
            }
        }
    }

    private class RecordingTransactionManager(private val events: MutableList<String>) : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            events.add("begin")
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            events.add("commit")
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
            events.add("rollback")
        }
    }

    companion object {
        @JvmStatic
        fun failureCases(): Stream<Arguments> =
            Operation.values().flatMap { operation ->
                FailurePhase.values().map { phase -> Arguments.of(operation, phase) }
            }.stream()

        private fun invoke(reader: JdbcNoteBlockListReader, operation: Operation): Any =
            if (operation == Operation.COUNT) reader.count(7L) else reader.read(7L, 0)
    }
}
