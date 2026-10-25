package com.pawbridge.backend.user.internal.login.infrastructure

import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `저장된 비밀번호가 있고 제공자 필터가 없을 때__대소문자 무시 이메일로 찾으면__트랜잭션 없이 원래의 계정을 돌려준다`(
        password: String?,
    ) {
        // given
        val f = Fixture()
        f.jdbc.password = password

        // when
        val account = f.login.findByEmail("MIXED@fixture.invalid").orElseThrow()

        // then
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
        verify { f.tokens wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `이메일이 없거나 유일하지 않을 때__찾으면__옵셔널 또는 단일 결과 실패를 그대로 유지한다`(count: Int) {
        // given
        val f = Fixture()
        f.jdbc.userRows = count

        // when
        // then
        if (count == 0) {
            assertTrue(f.login.findByEmail("synthetic@fixture.invalid").isEmpty)
        } else {
            assertThrows(IncorrectResultSizeDataAccessException::class.java) {
                f.login.findByEmail("synthetic@fixture.invalid")
            }
        }
        assertEquals(listOf("user-query"), f.events)
        verify { f.tokens wasNot Called }
    }

    @Test
    fun `인증된 계정일 때__완료하면__액세스 토큰을 먼저 발급하고 한 트랜잭션에서 세션을 만든다`() {
        // given
        val f = Fixture()
        val issued = IssuedRefreshToken("synthetic-session-token", 1_209_600)

        every { f.tokens.access(any()) } answers {
            f.events.add("access")
            "safe-access"
        }
        every { f.sessions.create(any()) } answers {
            assertTrue(f.tx.active)
            f.events.add("create-session")
            issued
        }

        // when
        val result = f.login.complete(ACCOUNT)

        // then
        assertEquals("safe-access", result.accessToken)
        assertSame(issued, result.refreshToken)
        assertEquals("LocalLogin.CompletedLogin", result.toString())
        assertEquals(listOf("access", "begin", "create-session", "commit"), f.events)
        verify(exactly = 1) {
            f.tokens.access(JwtSessionTokens.UserClaims(7, ACCOUNT.email, ACCOUNT.name, ACCOUNT.role, ACCOUNT.careRegNo))
        }
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `세션 쓰기나 커밋이 실패했을 때__완료하면__재시도나 성공 결과 없이 예외를 전파한다`(
        commit: Boolean,
    ) {
        // given
        val f = Fixture()
        val failure: RuntimeException = if (commit) {
            TransactionSystemException("synthetic commit")
        } else {
            DataAccessResourceFailureException("synthetic write")
        }

        every { f.tokens.access(any()) } answers {
            f.events.add("access")
            "safe-access"
        }
        if (commit) {
            f.tx.commitFailure = failure
            every { f.sessions.create(any()) } returns IssuedRefreshToken("synthetic-session-token", 1)
        } else {
            every { f.sessions.create(any()) } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.login.complete(ACCOUNT) }

        // then
        assertSame(failure, thrown)
        assertEquals(listOf("access", "begin", if (commit) "commit" else "rollback"), f.events)
        verify(exactly = 1) { f.sessions.create(7L) }
    }

    private class Fixture {
        val events = ArrayList<String>()
        val tx = Tx(events)
        val jdbc = RecordingJdbc(tx, events)
        val tokens = mockk<JwtSessionTokens>()
        val sessions = mockk<JdbcAuthSessionStore>()
        val login: JdbcLocalLogin = JdbcLocalLogin(jdbc, TransactionTemplate(tx), tokens, sessions)
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
                        val row = mockk<ResultSet>()
                        every { row.getLong("user_id") } returns 7L
                        every { row.getString("email") } returns ACCOUNT.email
                        every { row.getString("name") } returns ACCOUNT.name
                        every { row.getString("password") } returns password
                        every { row.getString("role") } returns ACCOUNT.role
                        every { row.getString("care_reg_no") } returns ACCOUNT.careRegNo
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
