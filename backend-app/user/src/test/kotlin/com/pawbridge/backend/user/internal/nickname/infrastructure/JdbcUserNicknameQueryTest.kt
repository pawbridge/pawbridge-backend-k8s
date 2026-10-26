package com.pawbridge.backend.user.internal.nickname.infrastructure

import com.pawbridge.backend.user.api.UserNicknameUnavailableException
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
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.util.Optional
import javax.sql.rowset.CachedRowSet
import javax.sql.rowset.RowSetMetaDataImpl
import javax.sql.rowset.RowSetProvider

class JdbcUserNicknameQueryTest {
    @Test
    fun `회원이 없을 때__닉네임을 조회하면__트랜잭션이 끝난 뒤 빈 값을 돌려준다`() {
        // given
        Fixture().use { fixture ->
            // when
            val nickname: Optional<String> = fixture.query.findNickname(41L)

            // then
            assertEquals(Optional.empty<String>(), nickname)
            assertFalse(fixture.transactions.active)
            assertEquals(1, fixture.transactions.commits)
            assertEquals("SELECT nickname FROM pawbridge_user.users WHERE user_id = ?", fixture.jdbc.sql)
            assertArrayEquals(arrayOf<Any?>(41L), fixture.jdbc.arguments)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["산책하는강아지", "", " "])
    fun `저장된 닉네임이 있을 때__닉네임을 조회하면__트랜잭션이 끝난 뒤에도 값을 보존한다`(
        storedNickname: String,
    ) {
        // given
        Fixture(storedNickname).use { fixture ->
            // when
            val nickname: Optional<String> = fixture.query.findNickname(52L)

            // then
            assertEquals(Optional.of(storedNickname), nickname)
            assertFalse(fixture.transactions.active)
            assertEquals(1, fixture.transactions.commits)
        }
    }

    @Test
    fun `JDBC가 실패했을 때__닉네임을 조회하면__롤백 뒤 조회 불가 예외를 던진다`() {
        // given
        Fixture().use { fixture ->
            val failure = DataAccessResourceFailureException("sensitive connection details")
            fixture.jdbc.failure = failure


            // when
            val exception = assertThrows(UserNicknameUnavailableException::class.java) { fixture.query.findNickname(41L) }

            // then
            assertSame(failure, exception.cause)
            assertEquals("User nickname lookup is unavailable.", exception.message)
            assertFalse(fixture.transactions.active)
            assertEquals(1, fixture.transactions.rollbacks)
        }
    }

    @Test
    fun `트랜잭션 시작이 실패했을 때__닉네임을 조회하면__쿼리 없이 조회 불가 예외를 던진다`() {
        // given
        Fixture().use { fixture ->
            val failure = CannotCreateTransactionException("sensitive transaction details")
            fixture.transactions.startFailure = failure


            // when
            val exception = assertThrows(UserNicknameUnavailableException::class.java) { fixture.query.findNickname(41L) }

            // then
            assertSame(failure, exception.cause)
            assertEquals("User nickname lookup is unavailable.", exception.message)
            assertEquals(0, fixture.jdbc.queryCount)
            assertFalse(fixture.transactions.active)
        }
    }

    @Test
    fun `트랜잭션 완료가 실패했을 때__닉네임을 조회하면__조회 불가 예외를 던진다`() {
        // given
        Fixture("산책하는강아지").use { fixture ->
            val failure = TransactionSystemException("sensitive completion details")
            fixture.transactions.completionFailure = failure


            // when
            val exception = assertThrows(UserNicknameUnavailableException::class.java) { fixture.query.findNickname(41L) }

            // then
            assertSame(failure, exception.cause)
            assertEquals("User nickname lookup is unavailable.", exception.message)
            assertEquals(1, fixture.jdbc.queryCount)
            assertFalse(fixture.transactions.active)
        }
    }

    private class Fixture(vararg nicknames: String) : AutoCloseable {
        val transactions = RecordingTransactionManager()
        val rows: CachedRowSet
        val jdbc: StubJdbcTemplate
        val query: JdbcUserNicknameQuery

        init {
            val metadata = RowSetMetaDataImpl()
            metadata.setColumnCount(1)
            metadata.setColumnName(1, "nickname")
            metadata.setColumnType(1, Types.VARCHAR)
            metadata.setNullable(1, ResultSetMetaData.columnNoNulls)
            rows = RowSetProvider.newFactory().createCachedRowSet()
            rows.setMetaData(metadata)
            for (nickname in nicknames) {
                rows.moveToInsertRow()
                rows.updateString(1, nickname)
                rows.insertRow()
            }
            rows.moveToCurrentRow()
            rows.beforeFirst()
            jdbc = StubJdbcTemplate(rows, transactions)
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            query = JdbcUserNicknameQuery(jdbc, readOnlyTransaction)
        }

        override fun close() {
            rows.close()
        }
    }

    private class StubJdbcTemplate(
        private val rows: CachedRowSet,
        private val transactions: RecordingTransactionManager,
    ) : JdbcTemplate() {
        var failure: RuntimeException? = null
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queryCount = 0

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            assertTrue(transactions.active, "The nickname SELECT must run inside its transaction.")
            this.sql = sql
            this.arguments = args
            queryCount++
            if (failure != null) {
                throw failure!!
            }
            try {
                return rse.extractData(rows)
            } catch (exception: SQLException) {
                throw UncategorizedSQLException("nickname lookup", sql, exception)
            }
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
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
