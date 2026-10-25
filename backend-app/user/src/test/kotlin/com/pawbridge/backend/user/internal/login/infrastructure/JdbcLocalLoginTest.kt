package com.pawbridge.backend.user.internal.login.infrastructure

import com.pawbridge.backend.user.anyNonNull
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException

class JdbcLocalLoginTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["OAuth2", "synthetic-hash"])
    fun given__stored_password_without_provider_filter__when__finding_casefold_email__then__return_original_account_without_transaction(
        password: String?,
    ) {
        val f = Fixture()
        f.jdbc.password = password

        val account = f.login.findByEmail("MIXED@fixture.invalid").orElseThrow()

        assertEquals(password, account.password)
        assertEquals(7L, account.userId)
        assertEquals("ROLE_SHELTER", account.role)
        assertEquals(
            "SELECT user_id, email, name, password, role, care_reg_no FROM pawbridge_user.users WHERE lower(email) = lower(?)",
            f.jdbc.lastSql,
        )
        assertArrayEquals(arrayOf<Any?>("MIXED@fixture.invalid"), f.jdbc.lastArgs)
        assertEquals(listOf("user-query"), f.events)
        assertEquals("LocalLogin.Account", account.toString())
        verifyNoInteractions(f.tokens)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun given__absent_or_nonunique_email__when__finding__then__preserve_optional_or_single_result_failure(count: Int) {
        val f = Fixture()
        f.jdbc.userRows = count

        if (count == 0) {
            assertTrue(f.login.findByEmail("synthetic@fixture.invalid").isEmpty)
        } else {
            assertThrows(IncorrectResultSizeDataAccessException::class.java) {
                f.login.findByEmail("synthetic@fixture.invalid")
            }
        }
        assertEquals(listOf("user-query"), f.events)
        verifyNoInteractions(f.tokens)
    }

    @Test
    fun given__authenticated_account__when__completing__then__issue_access_first_and_create_a_session_in_one_transaction() {
        val f = Fixture()
        val issued = IssuedRefreshToken("synthetic-session-token", 1_209_600)
        `when`(f.sessions.create(7L)).thenAnswer {
            assertTrue(f.tx.active)
            f.events.add("create-session")
            issued
        }

        val result = f.login.complete(ACCOUNT)

        assertEquals("safe-access", result.accessToken)
        assertSame(issued, result.refreshToken)
        assertEquals("LocalLogin.CompletedLogin", result.toString())
        assertEquals(listOf("access", "begin", "create-session", "commit"), f.events)
        verify(f.tokens).access(JwtSessionTokens.UserClaims(7, ACCOUNT.email, ACCOUNT.name, ACCOUNT.role, ACCOUNT.careRegNo))
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__session_write_or_commit_failure__when__completing__then__propagate_without_retry_or_success_result(
        commit: Boolean,
    ) {
        val f = Fixture()
        val failure: RuntimeException = if (commit) {
            TransactionSystemException("synthetic commit")
        } else {
            DataAccessResourceFailureException("synthetic write")
        }
        if (commit) {
            f.tx.commitFailure = failure
            `when`(f.sessions.create(7L)).thenReturn(IssuedRefreshToken("synthetic-session-token", 1))
        } else {
            `when`(f.sessions.create(7L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.login.complete(ACCOUNT) })

        assertEquals(listOf("access", "begin", if (commit) "commit" else "rollback"), f.events)
        verify(f.sessions).create(7L)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val tx = Tx(events)
        val jdbc = RecordingJdbc(tx, events)
        val tokens: JwtSessionTokens = mock(JwtSessionTokens::class.java)
        val sessions: JdbcAuthSessionStore = mock(JdbcAuthSessionStore::class.java)
        val login: JdbcLocalLogin

        init {
            `when`(tokens.access(anyNonNull<JwtSessionTokens.UserClaims>())).thenAnswer {
                events.add("access")
                "safe-access"
            }
            login = JdbcLocalLogin(jdbc, TransactionTemplate(tx), tokens, sessions)
        }
    }

    private class RecordingJdbc(
        private val tx: Tx,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        var password: String? = "synthetic-hash"
        var lastSql: String? = null
        var lastArgs: Array<out Any?>? = null
        var userRows = 1

        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            assertFalse(tx.active)
            lastSql = sql
            lastArgs = args
            val result = ArrayList<T>()
            try {
                if (sql.contains("FROM pawbridge_user.users")) {
                    events.add("user-query")
                    for (i in 0 until userRows) {
                        val row = mock(ResultSet::class.java)
                        `when`(row.getLong("user_id")).thenReturn(7L)
                        `when`(row.getString("email")).thenReturn(ACCOUNT.email)
                        `when`(row.getString("name")).thenReturn(ACCOUNT.name)
                        `when`(row.getString("password")).thenReturn(password)
                        `when`(row.getString("role")).thenReturn(ACCOUNT.role)
                        `when`(row.getString("care_reg_no")).thenReturn(ACCOUNT.careRegNo)
                        result.add(rowMapper.mapRow(row, i)!!)
                    }
                } else {
                    throw AssertionError("Unexpected query")
                }
                return result
            } catch (failure: SQLException) {
                throw AssertionError(failure)
            }
        }
    }

    private class Tx(private val events: MutableList<String>) : PlatformTransactionManager {
        var active = false
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            active = true
            events.add("begin")
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            events.add("commit")
            active = false
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            events.add("rollback")
            active = false
        }
    }

    companion object {
        private val ACCOUNT = LocalLogin.Account(7, "stored@fixture.invalid", "Stored", "synthetic-hash", "ROLE_SHELTER", "CARE")
    }
}
