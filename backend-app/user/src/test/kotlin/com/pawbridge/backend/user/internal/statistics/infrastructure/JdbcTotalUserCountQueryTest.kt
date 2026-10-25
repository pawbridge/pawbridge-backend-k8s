package com.pawbridge.backend.user.internal.statistics.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class JdbcTotalUserCountQueryTest {
    @Test
    fun `실제 회원 수가 int 범위를 넘을 때__회원 수를 세면__id 컬럼만 한 번 조회하고 읽기 전용 커밋 뒤 long 그대로 돌려준다`() {
        // given
        val fixture = Fixture()

        // when
        val count = fixture.query.count()

        // then
        assertEquals(5_000_000_000L, count)
        assertEquals("SELECT count(user_id) FROM pawbridge_user.users", fixture.jdbc.sql)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `집계 결과가 null이었다가 복구됐을 때__회원 수를 세면__0을 돌려주지 않고 실패한 뒤 다음 조회는 성공한다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.count = null

        // when
        assertThrows(NullPointerException::class.java) { fixture.query.count() }
        val queriesAfterFailure = fixture.jdbc.queries
        val activeAfterFailure = fixture.transactions.active
        fixture.jdbc.count = 7L
        val recovered = fixture.query.count()

        // then
        assertEquals(1, queriesAfterFailure)
        assertFalse(activeAfterFailure)
        assertEquals(7L, recovered)
        assertEquals(2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `조회나 트랜잭션이 실패했다가 복구됐을 때__회원 수를 세면__0이나 대체 값 없이 같은 예외를 전파하고 다음 조회는 성공한다`(
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY_DAO -> DataAccessResourceFailureException("synthetic-private-user-count-sql")
            FailurePhase.QUERY_RUNTIME -> IllegalStateException("synthetic-private-count-runtime")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY_DAO, FailurePhase.QUERY_RUNTIME -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.query.count() }
        val queriesAfterFailure = fixture.jdbc.queries
        val rollbacksAfterFailure = fixture.transactions.rollbacks
        val activeAfterFailure = fixture.transactions.active
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null
        val recovered = fixture.query.count()

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, queriesAfterFailure)
        assertEquals(
            if (phase == FailurePhase.QUERY_DAO || phase == FailurePhase.QUERY_RUNTIME) 1 else 0,
            rollbacksAfterFailure,
        )
        assertFalse(activeAfterFailure)
        assertEquals(5_000_000_000L, recovered)
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val query: JdbcTotalUserCountQuery

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            query = JdbcTotalUserCountQuery(jdbc, transaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var count: Long? = 5_000_000_000L
        var sql: String? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T> queryForObject(sql: String, requiredType: Class<T>): T? {
            assertTrue(transactions.active)
            assertEquals(Long::class.javaObjectType, requiredType)
            this.sql = sql
            queries++
            if (failure != null) {
                throw failure!!
            }
            return requiredType.cast(count)
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
}
