package com.pawbridge.backend.user.internal.profile.infrastructure

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.stream.Stream

class JdbcUserProfileQueryTest {
    @Test
    fun `저장된 프로필이 있을 때__필수 조회하면__일곱 컬럼만 조회하고 읽기 전용 커밋 뒤 그대로 돌려준다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_ADMIN", CREATED)

        // when
        val profile = fixture.query.require(52L)

        // then
        assertEquals(fixture.expected(), profile)
        assertEquals(
            "SELECT user_id, email, name, nickname, provider, role, created_at " +
                "FROM pawbridge_user.users WHERE user_id = ?",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(52L), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `생성 시각이 null일 때__필수 조회하면__트랜잭션이 끝난 뒤에도 null을 그대로 유지한다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_USER", null)

        // when
        val profile = fixture.query.require(52L)

        // then
        assertEquals(fixture.expected(), profile)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("storedStrings")
    fun `빈 문자열이나 공백 닉네임과 데이터베이스 역할이 저장돼 있을 때__필수 조회하면__값을 그대로 보존한다`(
        nickname: String,
        role: String,
    ) {
        // given
        val fixture = Fixture(nickname, role, CREATED)

        // when
        val profile = fixture.query.require(52L)

        // then
        assertEquals(fixture.expected(), profile)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `회원이 없을 때__필수 조회하면__트랜잭션 안에서 찾을 수 없음 예외를 던지고 롤백한다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED, found = false)

        // when
        val failure = assertThrows(UserNotFoundException::class.java) { fixture.query.require(41L) }

        // then
        assertEquals("존재하지 않는 회원입니다.", failure.message)
        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `JDBC 조회가 실패했다가 복구됐을 때__필수 조회하면__원래 예외로 롤백하고 다음 요청은 커밋한다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = DataAccessResourceFailureException("synthetic private SQL failure")
        fixture.jdbc.failure = failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { fixture.query.require(52L) }
        val queriesAfterFailure = fixture.jdbc.queryCount
        val rollbacksAfterFailure = fixture.transactions.rollbacks
        val commitsAfterFailure = fixture.transactions.commits
        val activeAfterFailure = fixture.transactions.active
        fixture.jdbc.failure = null
        val recovered = fixture.query.require(52L)

        // then
        assertSame(failure, thrown)
        assertEquals(1, queriesAfterFailure)
        assertEquals(1, rollbacksAfterFailure)
        assertEquals(0, commitsAfterFailure)
        assertFalse(activeAfterFailure)
        assertEquals(fixture.expected(), recovered)
        assertEquals(2, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 시작이 실패했다가 복구됐을 때__필수 조회하면__조회 없이 실패하고 다음 요청은 커밋한다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = CannotCreateTransactionException("synthetic private start failure")
        fixture.transactions.startFailure = failure

        // when
        val thrown = assertThrows(CannotCreateTransactionException::class.java) { fixture.query.require(52L) }
        val queriesAfterFailure = fixture.jdbc.queryCount
        val commitsAfterFailure = fixture.transactions.commits
        val rollbacksAfterFailure = fixture.transactions.rollbacks
        val activeAfterFailure = fixture.transactions.active
        fixture.transactions.startFailure = null
        val recovered = fixture.query.require(52L)

        // then
        assertSame(failure, thrown)
        assertEquals(0, queriesAfterFailure)
        assertEquals(0, commitsAfterFailure)
        assertEquals(0, rollbacksAfterFailure)
        assertFalse(activeAfterFailure)
        assertEquals(fixture.expected(), recovered)
        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 완료가 실패했다가 복구됐을 때__필수 조회하면__프로필을 돌려주지 않고 다음 요청은 커밋한다`() {
        // given
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = TransactionSystemException("synthetic private completion failure")
        fixture.transactions.completionFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { fixture.query.require(52L) }
        val queriesAfterFailure = fixture.jdbc.queryCount
        val commitsAfterFailure = fixture.transactions.commits
        val activeAfterFailure = fixture.transactions.active
        fixture.transactions.completionFailure = null
        val recovered = fixture.query.require(52L)

        // then
        assertSame(failure, thrown)
        assertEquals(1, queriesAfterFailure)
        assertEquals(1, commitsAfterFailure)
        assertFalse(activeAfterFailure)
        assertEquals(fixture.expected(), recovered)
        assertEquals(2, fixture.jdbc.queryCount)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture(
        private val nickname: String,
        private val role: String,
        private val createdAt: LocalDateTime?,
        found: Boolean = true,
    ) {
        val transactions = RecordingTransactionManager()
        val rows: ResultSet = profileRow(found)
        val jdbc = StubJdbcTemplate(rows, transactions)
        val query: JdbcUserProfileQuery

        init {
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            readOnlyTransaction.timeout = 3
            query = JdbcUserProfileQuery(jdbc, readOnlyTransaction)
        }

        /**
         * In-memory ResultSet instead of a strict mock: the extractor reads seven columns through overloaded getters.
         * The created_at read also checks it happens inside the transaction with a UTC calendar.
         */
        private fun profileRow(found: Boolean): ResultSet {
            val handler = InvocationHandler { _, method, args ->
                when {
                    method.name == "next" -> found
                    method.name == "getLong" && args[0] == "user_id" -> 52L
                    method.name == "getString" && args[0] == "email" -> EMAIL
                    method.name == "getString" && args[0] == "name" -> NAME
                    method.name == "getString" && args[0] == "nickname" -> nickname
                    method.name == "getString" && args[0] == "provider" -> PROVIDER
                    method.name == "getString" && args[0] == "role" -> role
                    method.name == "getTimestamp" && args[0] == "created_at" -> {
                        assertTrue(transactions.active)
                        assertEquals("UTC", (args[1] as Calendar).timeZone.id)
                        if (createdAt == null) null else Timestamp.valueOf(createdAt)
                    }
                    else -> throw AssertionError("Unexpected ResultSet call: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }

        fun expected(): UserProfile {
            return UserProfile(52L, EMAIL, NAME, nickname, PROVIDER, role, createdAt)
        }
    }

    private class StubJdbcTemplate(
        private val rows: ResultSet,
        private val transactions: RecordingTransactionManager,
    ) : JdbcTemplate() {
        var failure: RuntimeException? = null
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queryCount = 0

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            assertTrue(transactions.active, "The profile SELECT and missing-user decision belong to the read transaction.")
            this.sql = sql
            this.arguments = args
            queryCount++
            if (failure != null) {
                throw failure!!
            }
            try {
                return rse.extractData(rows)
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("profile lookup", sql, failure)
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

    companion object {
        private val CREATED: LocalDateTime = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 123456000)
        private const val EMAIL = " Mixed.Case@example.invalid "
        private const val NAME = " 홍길동 "
        private const val PROVIDER = "gOoGlE"

        @JvmStatic
        fun storedStrings(): Stream<Arguments> = Stream.of(
            Arguments.of("", "ROLE_USER"),
            Arguments.of(" \t ", "ROLE_ADMIN"),
            Arguments.of("반려친구", "ROLE_SHELTER"),
        )
    }
}
