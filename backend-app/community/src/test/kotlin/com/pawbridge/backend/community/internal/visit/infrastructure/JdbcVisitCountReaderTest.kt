package com.pawbridge.backend.community.internal.visit.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.time.LocalDate

class JdbcVisitCountReaderTest {

    @ParameterizedTest
    @ValueSource(longs = [0L, 3_000_000_001L, Long.MAX_VALUE])
    fun `저장된 집계 수가 있을 때__읽으면__정확한 SQL 날짜를 바인딩하고 읽기 전용 완료 뒤에도 Long 값을 보존한다`(
        count: Long,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(count)

        // when
        val result = fixture.reader.count(DAY)

        // then
        assertEquals(count, result)
        assertEquals(
            "SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(Date.valueOf(DAY)), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `일별 행이 없을 때__읽으면__읽기 전용 완료 뒤에 0을 돌려준다`() {
        // given
        val fixture = Fixture()

        // when
        val result = fixture.reader.count(DAY)

        // then
        assertEquals(0L, result)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `질의가 실패했다가 복구될 때__읽으면__0으로 대체하지 않고 전파하며 트랜잭션을 놓는다`() {
        // given
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-query")
        fixture.jdbc.failure = failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { fixture.reader.count(DAY) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)

        // given
        fixture.jdbc.failure = null
        fixture.jdbc.rows = listOf(77L)

        // when
        val recovered = fixture.reader.count(DAY)

        // then
        assertEquals(77L, recovered)
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 시작이 실패했다가 복구될 때__읽으면__시작에 성공하기 전에는 질의하지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-begin")
        fixture.transactions.startFailure = failure

        // when
        val thrown = assertThrows(CannotCreateTransactionException::class.java) { fixture.reader.count(DAY) }

        // then
        assertSame(failure, thrown)
        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)

        // given
        fixture.transactions.startFailure = null

        // when
        val recovered = fixture.reader.count(DAY)

        // then
        assertEquals(0L, recovered)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 완료가 실패했다가 복구될 때__읽으면__완료에 성공하기 전에는 집계 수를 돌려주지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { fixture.reader.count(DAY) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)

        // given
        fixture.transactions.completionFailure = null
        fixture.jdbc.rows = listOf(77L)

        // when
        val recovered = fixture.reader.count(DAY)

        // then
        assertEquals(77L, recovered)
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val reader: JdbcVisitCountReader

        init {
            val tx = TransactionTemplate(transactions)
            tx.isReadOnly = true
            tx.timeout = 3
            reader = JdbcVisitCountReader(jdbc, tx)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var rows: List<Long> = listOf()
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T : Any?> queryForList(sql: String, type: Class<T>, vararg arguments: Any?): List<T> {
            assertTrue(
                transactions.active,
                "The daily count must be read inside the supplied read-only transaction.",
            )
            assertEquals(java.lang.Long::class.java, type)
            this.sql = sql
            this.arguments = arguments
            queries++
            if (failure != null) {
                throw failure!!
            }
            return rows.map { type.cast(it) }
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
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
            rollbacks++
        }
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 10, 20)
    }
}
