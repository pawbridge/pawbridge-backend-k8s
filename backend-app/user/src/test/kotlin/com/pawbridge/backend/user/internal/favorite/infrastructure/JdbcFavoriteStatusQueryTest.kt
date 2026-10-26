package com.pawbridge.backend.user.internal.favorite.infrastructure

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

class JdbcFavoriteStatusQueryTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `즐겨찾기가 있거나 없을 때__확인하면__읽기 전용 커밋 뒤 두 id의 존재 쿼리가 불리언을 돌려준다`(
        exists: Boolean,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.exists = exists

        // when
        val result = fixture.query.isFavorite(52L, 73L)

        // then
        assertEquals(exists, result)
        assertEquals(
            "SELECT EXISTS ( SELECT 1 FROM pawbridge_user.favorites WHERE user_id = ? AND animal_id = ? )",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(52L, 73L), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE])
    fun `동물 id가 0이거나 음수이거나 경계값일 때__확인하면__양수 검증 없이 정확한 long 값으로 조회한다`(
        animalId: Long,
    ) {
        // given
        val fixture = Fixture()

        // when
        val result = fixture.query.isFavorite(52L, animalId)

        // then
        assertFalse(result)
        assertArrayEquals(arrayOf<Any?>(52L, animalId), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `JDBC 실패 뒤 복구될 때__확인하면__실패는 거짓으로 대체되지 않고 롤백되며 다음 조회는 커밋한다`() {
        // given
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-sql")
        fixture.jdbc.failure = failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { fixture.query.isFavorite(52L, 73L) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.failure = null
        fixture.jdbc.exists = true
        assertTrue(fixture.query.isFavorite(52L, 73L))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 시작 실패 뒤 복구될 때__확인하면__실패 전에 쿼리가 없고 다음 조회는 커밋한다`() {
        // given
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-start")
        fixture.transactions.startFailure = failure

        // when
        val thrown = assertThrows(CannotCreateTransactionException::class.java) { fixture.query.isFavorite(52L, 73L) }

        // then
        assertSame(failure, thrown)
        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        assertFalse(fixture.query.isFavorite(52L, 73L))
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 완료 실패 뒤 복구될 때__확인하면__완료에 성공하기 전에는 불리언을 돌려주지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { fixture.query.isFavorite(52L, 73L) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.transactions.completionFailure = null
        fixture.jdbc.exists = true
        assertTrue(fixture.query.isFavorite(52L, 73L))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val query: JdbcFavoriteStatusQuery

        init {
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            readOnlyTransaction.timeout = 3
            query = JdbcFavoriteStatusQuery(jdbc, readOnlyTransaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var exists = false
        var failure: RuntimeException? = null

        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            assertTrue(transactions.active, "The existence query must run inside the existing read-only transaction.")
            this.sql = sql
            this.arguments = args
            queries++
            if (failure != null) {
                throw failure!!
            }
            return requiredType.cast(exists)
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
