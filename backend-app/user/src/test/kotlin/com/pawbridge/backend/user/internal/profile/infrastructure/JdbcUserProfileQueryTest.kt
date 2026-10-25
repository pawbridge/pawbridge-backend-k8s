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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.stream.Stream

class JdbcUserProfileQueryTest {
    @Test
    fun given__stored_profile__when__required__then__exact_seven_column_projection_and_identity_return_after_read_only_commit() {
        val fixture = Fixture("반려친구", "ROLE_ADMIN", CREATED)

        assertEquals(fixture.expected(), fixture.query.require(52L))

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
    fun given__null_creation_timestamp__when__required__then__null_is_preserved_after_transaction_ends() {
        val fixture = Fixture("반려친구", "ROLE_USER", null)

        assertEquals(fixture.expected(), fixture.query.require(52L))

        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("storedStrings")
    fun given__stored_strings__when__required__then__empty_whitespace_and_database_role_are_preserved(
        nickname: String,
        role: String,
    ) {
        val fixture = Fixture(nickname, role, CREATED)

        assertEquals(fixture.expected(), fixture.query.require(52L))

        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__missing_user__when__required__then__not_found_is_raised_inside_transaction_and_rolls_back() {
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        `when`(fixture.rows.next()).thenReturn(false)

        val failure = assertThrows(UserNotFoundException::class.java) { fixture.query.require(41L) }

        assertEquals("존재하지 않는 회원입니다.", failure.message)
        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__jdbc_failure__when__required_then_recovered__then__original_failure_rolls_back_and_next_request_commits() {
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = DataAccessResourceFailureException("synthetic private SQL failure")
        fixture.jdbc.failure = failure

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { fixture.query.require(52L) })

        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.failure = null
        assertEquals(fixture.expected(), fixture.query.require(52L))
        assertEquals(2, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_start_failure__when__required_then_recovered__then__no_query_precedes_failure_and_next_request_commits() {
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = CannotCreateTransactionException("synthetic private start failure")
        fixture.transactions.startFailure = failure

        assertSame(failure, assertThrows(CannotCreateTransactionException::class.java) { fixture.query.require(52L) })

        assertEquals(0, fixture.jdbc.queryCount)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        assertEquals(fixture.expected(), fixture.query.require(52L))
        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_completion_failure__when__required_then_recovered__then__no_profile_is_returned_and_next_request_commits() {
        val fixture = Fixture("반려친구", "ROLE_USER", CREATED)
        val failure = TransactionSystemException("synthetic private completion failure")
        fixture.transactions.completionFailure = failure

        assertSame(failure, assertThrows(TransactionSystemException::class.java) { fixture.query.require(52L) })

        assertEquals(1, fixture.jdbc.queryCount)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.transactions.completionFailure = null
        assertEquals(fixture.expected(), fixture.query.require(52L))
        assertEquals(2, fixture.jdbc.queryCount)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture(
        private val nickname: String,
        private val role: String,
        private val createdAt: LocalDateTime?,
    ) {
        val transactions = RecordingTransactionManager()
        val rows: ResultSet = mock(ResultSet::class.java)
        val jdbc = StubJdbcTemplate(rows, transactions)
        val query: JdbcUserProfileQuery

        init {
            `when`(rows.next()).thenReturn(true)
            `when`(rows.getLong("user_id")).thenReturn(52L)
            `when`(rows.getString("email")).thenReturn(EMAIL)
            `when`(rows.getString("name")).thenReturn(NAME)
            `when`(rows.getString("nickname")).thenReturn(nickname)
            `when`(rows.getString("provider")).thenReturn(PROVIDER)
            `when`(rows.getString("role")).thenReturn(role)
            `when`(rows.getTimestamp(eq("created_at"), any(Calendar::class.java))).thenAnswer { invocation ->
                assertTrue(transactions.active)
                assertEquals("UTC", invocation.getArgument(1, Calendar::class.java).timeZone.id)
                if (createdAt == null) null else Timestamp.valueOf(createdAt)
            }
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            readOnlyTransaction.timeout = 3
            query = JdbcUserProfileQuery(jdbc, readOnlyTransaction)
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
