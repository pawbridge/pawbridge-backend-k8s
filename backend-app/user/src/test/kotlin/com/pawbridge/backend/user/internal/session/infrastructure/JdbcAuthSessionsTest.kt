package com.pawbridge.backend.user.internal.session.infrastructure

import com.pawbridge.backend.user.anyNonNull
import com.pawbridge.backend.user.eqNonNull
import org.mockito.ArgumentMatchers.eq
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.RetiredToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation
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
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Optional

class JdbcAuthSessionsTest {
    @ParameterizedTest
    @NullAndEmptySource
    fun given__no_refresh_cookie__when__refreshing__then__reject_as_missing_without_a_transaction(token: String?) {
        val f = Fixture()

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(token) }

        assertEquals(Reason.MISSING, failure.reason())
        assertEquals(listOf<String>(), f.tx.events)
        verifyNoInteractions(f.store)
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
    fun given__a_token_this_server_cannot_have_issued__when__refreshing__then__reject_without_touching_the_database(
        token: String,
    ) {
        val f = Fixture()

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(token) }

        assertEquals(Reason.INVALID, failure.reason())
        verifyNoInteractions(f.store)
        assertEquals(listOf<String>(), f.tx.events)
    }

    @Test
    fun given__an_active_session__when__refreshing__then__rotate_once_by_hash_and_return_current_claims_with_a_new_token() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.of(Rotation(31, 7, 1_209_600)))

        val result = assertInstanceOf(AuthSessions.Rotated::class.java, f.sessions.refresh(PRESENTED))

        val next = result.refreshToken!!.value!!
        assertNotEquals(PRESENTED, next)
        assertTrue(RefreshTokens.wellFormed(next))
        assertEquals(1_209_600L, result.refreshToken!!.maxAgeSeconds)
        assertEquals("synthetic-access", result.accessToken)
        verify(f.store).rotate(PRESENTED_HASH, RefreshTokens.hash(next))
        verify(f.store, never()).findRetired(anyString())
        verify(f.tokens).access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_USER", "CARE"))
        assertEquals(listOf("begin", "commit"), f.tx.events)
        assertArrayEquals(arrayOf<Any?>(7L), f.jdbc.userArgs)
    }

    @Test
    fun given__the_latest_retired_token_within_grace_whose_successor_was_never_used__when__refreshing__then__rotate_again_and_set_a_cookie() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(RetiredToken(31, 7, true, true, true)))
        `when`(f.store.rotateUnusedSuccessor(eq(31L), eqNonNull(PRESENTED_HASH), anyString()))
            .thenReturn(Optional.of(Rotation(31, 7, 1_209_000)))

        val result = assertInstanceOf(AuthSessions.Rotated::class.java, f.sessions.refresh(PRESENTED))

        assertEquals(1_209_000L, result.refreshToken!!.maxAgeSeconds)
        verify(f.store).rotateUnusedSuccessor(31, PRESENTED_HASH, RefreshTokens.hash(result.refreshToken!!.value!!))
        verify(f.store, never()).revoke(anyLong())
        assertEquals(listOf("begin", "commit"), f.tx.events)
    }

    @Test
    fun given__a_concurrent_request_already_moved_the_session_on__when__refreshing_within_grace__then__return_access_only_without_a_new_token() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(RetiredToken(31, 7, true, true, true)))
        `when`(f.store.rotateUnusedSuccessor(anyLong(), anyString(), anyString())).thenReturn(Optional.empty())

        val result = assertInstanceOf(AuthSessions.AccessOnly::class.java, f.sessions.refresh(PRESENTED))

        assertEquals("synthetic-access", result.accessToken)
        verify(f.store, never()).revoke(anyLong())
    }

    @Test
    fun given__an_older_generation_retired_within_grace__when__refreshing__then__return_access_only_without_rotating_again() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(RetiredToken(31, 7, true, false, true)))

        assertInstanceOf(AuthSessions.AccessOnly::class.java, f.sessions.refresh(PRESENTED))

        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString())
        verify(f.store, never()).revoke(anyLong())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__a_token_of_any_generation_retired_before_the_grace_window__when__refreshing__then__revoke_only_that_session_and_reject_after_commit(
        latestRetirement: Boolean,
    ) {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH))
            .thenReturn(Optional.of(RetiredToken(31, 7, false, latestRetirement, true)))

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        assertEquals(Reason.REUSED, failure.reason())
        assertEquals("유효하지 않은 토큰입니다.", failure.message)
        verify(f.store).revoke(31)
        verify(f.store, never()).revokeAll(anyLong())
        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString())
        assertEquals(listOf("begin", "commit"), f.tx.events)
        verifyNoInteractions(f.tokens)
    }

    @Test
    fun given__a_token_retired_moments_ago_on_a_session_that_is_no_longer_active__when__refreshing__then__reject_without_revoking() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(RetiredToken(31, 7, true, true, false)))

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        assertEquals(Reason.INVALID, failure.reason())
        verify(f.store, never()).revoke(anyLong())
        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString())
        verifyNoInteractions(f.tokens)
    }

    /** Idle-expired, absolutely expired, revoked and unknown tokens all fail the rotation WHERE clause. */
    @Test
    fun given__an_expired_revoked_or_unknown_token__when__refreshing__then__reject_as_invalid_without_writes() {
        val f = Fixture()
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.empty())

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        assertEquals(Reason.INVALID, failure.reason())
        verify(f.store, never()).revoke(anyLong())
        verifyNoInteractions(f.tokens)
        assertEquals(listOf("begin", "commit"), f.tx.events)
    }

    @Test
    fun given__the_user_disappears_after_rotation__when__refreshing__then__roll_back_the_rotation_and_reject() {
        val f = Fixture()
        f.jdbc.userPresent = false
        `when`(f.store.rotate(anyString(), anyString())).thenReturn(Optional.of(Rotation(31, 7, 10)))

        val failure = assertThrows(AuthSessionRejectedException::class.java) { f.sessions.refresh(PRESENTED) }

        assertEquals(Reason.INVALID, failure.reason())
        assertEquals(listOf("begin", "rollback"), f.tx.events)
    }

    @Test
    fun given__the_same_cookie_twice__when__logging_out__then__revoke_by_hash_each_time_without_failing() {
        val f = Fixture()

        f.sessions.logout(PRESENTED)
        f.sessions.logout(PRESENTED)

        verify(f.store, times(2)).revokeByTokenHash(PRESENTED_HASH)
        assertEquals(listOf("begin", "commit", "begin", "commit"), f.tx.events)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["not-a-refresh-token"])
    fun given__no_or_malformed_cookie__when__logging_out__then__do_nothing(token: String?) {
        val f = Fixture()

        f.sessions.logout(token)

        verifyNoInteractions(f.store)
        assertEquals(listOf<String>(), f.tx.events)
    }

    private class Fixture {
        val tx = Tx()
        val jdbc = UserJdbc(tx)
        val store: JdbcAuthSessionStore = mock(JdbcAuthSessionStore::class.java)
        val tokens: JwtSessionTokens = mock(JwtSessionTokens::class.java)
        val sessions: JdbcAuthSessions

        init {
            `when`(tokens.access(anyNonNull<JwtSessionTokens.UserClaims>())).thenReturn("synthetic-access")
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
            try {
                val row = mock(ResultSet::class.java)
                `when`(row.getLong("user_id")).thenReturn(7L)
                `when`(row.getString("email")).thenReturn("synthetic@fixture.invalid")
                `when`(row.getString("name")).thenReturn("Synthetic")
                `when`(row.getString("role")).thenReturn("ROLE_USER")
                `when`(row.getString("care_reg_no")).thenReturn("CARE")
                rows.add(rowMapper.mapRow(row, 0) as T)
                return rows
            } catch (failure: SQLException) {
                throw AssertionError(failure)
            }
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
        private const val PRESENTED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private val PRESENTED_HASH = RefreshTokens.hash(PRESENTED)
    }
}
