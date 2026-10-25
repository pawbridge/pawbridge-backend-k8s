package com.pawbridge.backend.user.internal.contact.infrastructure

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
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

class JdbcContactMemberQueryTest {
    @ParameterizedTest
    @EnumSource(MemberState::class)
    fun given__active_missing_or_pending_user__when__reading__then__return_exact_contact_projection_after_read_only_completion(
        state: MemberState,
    ) {
        val fixture = Fixture()
        `when`(fixture.rows.next()).thenReturn(state != MemberState.MISSING)
        `when`(fixture.rows.getBoolean("pending")).thenReturn(state == MemberState.PENDING)

        val found = fixture.query.get(USER_ID)

        assertEquals(
            ContactMember(
                USER_ID,
                if (state == MemberState.ACTIVE) NICKNAME else "탈퇴한 회원",
                state == MemberState.ACTIVE,
                state == MemberState.PENDING,
            ),
            found,
        )
        assertEquals(
            "SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending FROM pawbridge_user.users u " +
                "LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id WHERE u.user_id = ?",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(USER_ID), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        verify(fixture.rows).next()
        if (state != MemberState.MISSING) {
            verify(fixture.rows).getBoolean("pending")
            verify(fixture.rows).getLong("user_id")
        }
        if (state == MemberState.ACTIVE) {
            verify(fixture.rows).getString("nickname")
        }
        verifyNoMoreInteractions(fixture.rows)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__user_query_or_transaction_runtime_then_recovery__when__reading__then__preserve_failure_as_typed_unavailable_without_empty_fallback(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY -> DataAccessResourceFailureException("synthetic-private-sql")
            FailurePhase.MAP -> IllegalStateException("synthetic-private-mapping")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY -> fixture.jdbc.failure = failure
            FailurePhase.MAP -> `when`(fixture.rows.getBoolean("pending")).thenThrow(failure)
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        val unavailable = assertThrows(ContactMemberUnavailableException::class.java) { fixture.query.get(USER_ID) }

        assertSame(failure, unavailable.cause)
        assertEquals("Contact member lookup is unavailable.", unavailable.message)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null
        doReturn(false).`when`(fixture.rows).getBoolean("pending")
        assertEquals(ContactMember(USER_ID, NICKNAME, true, false), fixture.query.get(USER_ID))
        assertFalse(fixture.transactions.active)
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
    }

    enum class MemberState { ACTIVE, MISSING, PENDING }

    enum class FailurePhase { BEGIN, QUERY, MAP, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val rows: ResultSet = mock(ResultSet::class.java)
        val jdbc = RecordingJdbc(rows, transactions)
        val query: JdbcContactMemberQuery

        init {
            `when`(rows.next()).thenReturn(true)
            `when`(rows.getLong("user_id")).thenReturn(USER_ID)
            `when`(rows.getString("nickname")).thenReturn(NICKNAME)
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            query = JdbcContactMemberQuery(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val rows: ResultSet,
        private val transactions: RecordingTransactionManager,
    ) : JdbcTemplate() {
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            assertTrue(transactions.active)
            this.sql = sql
            this.arguments = args
            queries++
            if (failure != null) {
                throw failure!!
            }
            try {
                return rse.extractData(rows)
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("contact lookup", sql, failure)
            }
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

    companion object {
        private const val USER_ID = 99L
        private const val NICKNAME = "  원문\n닉네임  "
    }
}
