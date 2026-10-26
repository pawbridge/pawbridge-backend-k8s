package com.pawbridge.backend.user.internal.session.infrastructure

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.RetiredToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.sql.ResultSet
import java.util.Optional

class JdbcAuthSessionsTest {
    @ParameterizedTest
    @NullAndEmptySource
    fun `리프레시 쿠키가 없을 때__갱신하면__트랜잭션 없이 누락으로 거부한다`(token: String?) {
        // given
        val f = Fixture()

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(token) }

        // then
        assertEquals(Reason.MISSING, failure.reason())
        assertEquals(listOf<String>(), f.tx.events)
        confirmVerified(f.store)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "short",
            "eyJhbGciOiJIUzI1NiJ9.e30.signature-of-an-old-jwt-refresh-token",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
        ],
    )
    fun `이 서버가 발급할 수 없는 토큰일 때__갱신하면__데이터베이스를 건드리지 않고 거부한다`(
        token: String,
    ) {
        // given
        val f = Fixture()

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(token) }

        // then
        assertEquals(Reason.INVALID, failure.reason())
        confirmVerified(f.store)
        assertEquals(listOf<String>(), f.tx.events)
    }

    @Test
    fun `활성 세션일 때__갱신하면__해시로 한 번 회전하고 현재 클레임으로 새 토큰을 돌려준다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.of(Rotation(31, 7, 1_209_600))
        every { f.tokens.access(any()) } returns "synthetic-access"

        // when
        val result = assertInstanceOf(AuthSessions.Rotated::class.java, f.sessions.refresh(PRESENTED))

        // then
        val next = result.refreshToken!!.value!!
        assertNotEquals(PRESENTED, next)
        assertTrue(RefreshTokens.wellFormed(next))
        assertEquals(1_209_600L, result.refreshToken!!.maxAgeSeconds)
        assertEquals("synthetic-access", result.accessToken)
        verify(exactly = 1) { f.store.rotate(PRESENTED_HASH, RefreshTokens.hash(next)) }
        verify(exactly = 0) { f.store.findRetired(any()) }
        verify(exactly = 1) {
            f.tokens.access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_USER", "CARE"))
        }
        assertEquals(listOf("begin", "commit"), f.tx.events)
        assertArrayEquals(arrayOf<Any?>(7L), f.jdbc.userArgs)
    }

    @Test
    fun `유예 시간 안에 폐기된 최신 토큰의 후속 토큰이 쓰이지 않았을 때__갱신하면__다시 회전하고 쿠키를 설정한다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.of(RetiredToken(31, 7, true, true, true))
        every { f.store.rotateUnusedSuccessor(any(), any(), any()) } returns Optional.of(Rotation(31, 7, 1_209_000))
        every { f.tokens.access(any()) } returns "synthetic-access"

        // when
        val result = assertInstanceOf(AuthSessions.Rotated::class.java, f.sessions.refresh(PRESENTED))

        // then
        assertEquals(1_209_000L, result.refreshToken!!.maxAgeSeconds)
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 1) {
            f.store.rotateUnusedSuccessor(31, PRESENTED_HASH, RefreshTokens.hash(result.refreshToken!!.value!!))
        }
        verify(exactly = 0) { f.store.revoke(any()) }
        assertEquals(listOf("begin", "commit"), f.tx.events)
    }

    @Test
    fun `동시 요청이 이미 세션을 다음 토큰으로 넘겼을 때__유예 시간 안에 갱신하면__새 토큰 없이 액세스 토큰만 돌려준다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.of(RetiredToken(31, 7, true, true, true))
        every { f.store.rotateUnusedSuccessor(any(), any(), any()) } returns Optional.empty()
        every { f.tokens.access(any()) } returns "synthetic-access"

        // when
        val result = assertInstanceOf(AuthSessions.AccessOnly::class.java, f.sessions.refresh(PRESENTED))

        // then
        assertEquals("synthetic-access", result.accessToken)
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 0) { f.store.revoke(any()) }
    }

    @Test
    fun `유예 시간 안에 폐기된 이전 세대 토큰일 때__갱신하면__다시 회전하지 않고 액세스 토큰만 돌려준다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.of(RetiredToken(31, 7, true, false, true))
        every { f.tokens.access(any()) } returns "synthetic-access"

        // when
        val result = f.sessions.refresh(PRESENTED)

        // then
        assertInstanceOf(AuthSessions.AccessOnly::class.java, result)
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 0) { f.store.rotateUnusedSuccessor(any(), any(), any()) }
        verify(exactly = 0) { f.store.revoke(any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `유예 시간이 지난 뒤 폐기된 어떤 세대의 토큰일 때__갱신하면__그 세션만 폐기하고 커밋 뒤 거부한다`(
        latestRetirement: Boolean,
    ) {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.of(RetiredToken(31, 7, false, latestRetirement, true))
        justRun { f.store.revoke(any()) }

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        // then
        assertEquals(Reason.REUSED, failure.reason())
        assertEquals("유효하지 않은 토큰입니다.", failure.message)
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 1) { f.store.revoke(31) }
        verify(exactly = 0) { f.store.revokeAll(any()) }
        verify(exactly = 0) { f.store.rotateUnusedSuccessor(any(), any(), any()) }
        assertEquals(listOf("begin", "commit"), f.tx.events)
        confirmVerified(f.tokens)
    }

    @Test
    fun `방금 폐기된 토큰이지만 세션이 더는 활성이 아닐 때__갱신하면__폐기하지 않고 거부한다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.of(RetiredToken(31, 7, true, true, false))

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        // then
        assertEquals(Reason.INVALID, failure.reason())
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 0) { f.store.revoke(any()) }
        verify(exactly = 0) { f.store.rotateUnusedSuccessor(any(), any(), any()) }
        confirmVerified(f.tokens)
    }

    /** Idle-expired, absolutely expired, revoked and unknown tokens all fail the rotation WHERE clause. */
    @Test
    fun `만료되었거나 폐기되었거나 알 수 없는 토큰일 때__갱신하면__쓰기 없이 유효하지 않음으로 거부한다`() {
        // given
        val f = Fixture()

        every { f.store.rotate(any(), any()) } returns Optional.empty()
        every { f.store.findRetired(any()) } returns Optional.empty()

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        // then
        assertEquals(Reason.INVALID, failure.reason())
        verify(exactly = 1) { f.store.findRetired(PRESENTED_HASH) }
        verify(exactly = 0) { f.store.revoke(any()) }
        confirmVerified(f.tokens)
        assertEquals(listOf("begin", "commit"), f.tx.events)
    }

    @Test
    fun `회전 뒤 사용자가 사라졌을 때__갱신하면__회전을 롤백하고 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.userPresent = false

        every { f.store.rotate(any(), any()) } returns Optional.of(Rotation(31, 7, 10))

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        // then
        assertEquals(Reason.INVALID, failure.reason())
        assertEquals(listOf("begin", "rollback"), f.tx.events)
    }

    @Test
    fun `같은 쿠키로 두 번 로그아웃할 때__로그아웃하면__실패 없이 매번 해시로 폐기한다`() {
        // given
        val f = Fixture()

        justRun { f.store.revokeByTokenHash(any()) }

        // when
        f.sessions.logout(PRESENTED)
        f.sessions.logout(PRESENTED)

        // then
        verify(exactly = 2) { f.store.revokeByTokenHash(PRESENTED_HASH) }
        assertEquals(listOf("begin", "commit", "begin", "commit"), f.tx.events)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["not-a-refresh-token"])
    fun `쿠키가 없거나 형식이 잘못됐을 때__로그아웃하면__아무 일도 하지 않는다`(token: String?) {
        // given
        val f = Fixture()

        // when
        f.sessions.logout(token)

        // then
        confirmVerified(f.store)
        assertEquals(listOf<String>(), f.tx.events)
    }

    private class Fixture {
        val tx = Tx()
        val jdbc = UserJdbc(tx)
        val store: JdbcAuthSessionStore = mockk<JdbcAuthSessionStore>()
        val tokens: JwtSessionTokens = mockk<JwtSessionTokens>()
        val sessions: JdbcAuthSessions

        init {
            val refreshTokens = RefreshTokens(CountingRandom())
            sessions = JdbcAuthSessions(jdbc, TransactionTemplate(tx), tokens, store, refreshTokens)
        }
    }

    /** Deterministic, distinct bytes so generated tokens differ from the fixed presented token. */
    private class CountingRandom : SecureRandom() {
        private var next: Byte = 1

        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = next++
            }
        }
    }

    private class UserJdbc(private val tx: Tx) : JdbcTemplate() {
        var userPresent = true
        var userArgs: Array<out Any?>? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            assertTrue(tx.active)
            assertEquals("SELECT user_id, email, name, role, care_reg_no FROM pawbridge_user.users WHERE user_id = ?", sql)
            userArgs = args
            val rows = ArrayList<T>()
            if (!userPresent) {
                return rows
            }
            val row = resultSet(
                mapOf(
                    "user_id" to 7L,
                    "email" to "synthetic@fixture.invalid",
                    "name" to "Synthetic",
                    "role" to "ROLE_USER",
                    "care_reg_no" to "CARE",
                ),
            )
            rows.add(rowMapper.mapRow(row, 0) as T)
            return rows
        }
    }

    private class Tx : PlatformTransactionManager {
        val events = ArrayList<String>()
        var active = false

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            active = true
            events.add("begin")
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            events.add("commit")
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            events.add("rollback")
        }
    }

    companion object {
        /** In-memory ResultSet that answers typed getters by column label; any other call fails the test. */
        private fun resultSet(values: Map<String, Any?>): ResultSet {
            val handler = InvocationHandler { _, method, args ->
                if (method.name.startsWith("get") && args != null && args[0] is String && values.containsKey(args[0])) {
                    values[args[0]]
                } else {
                    throw AssertionError("Unexpected ResultSet call: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }

        private const val PRESENTED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private val PRESENTED_HASH = RefreshTokens.hash(PRESENTED)
    }
}
