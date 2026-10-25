package com.pawbridge.backend.user.internal.session.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration

/**
 * Statement and binding contract. Row semantics (expiry, races, eviction order) are exercised against PostgreSQL
 * in PostgresqlAuthSessionCompatibilityTest.
 */
class JdbcAuthSessionStoreTest {
    @Test
    fun given__a_login__when__creating__then__lock_the_user_store_only_the_hash_and_keep_the_five_most_recent_sessions() {
        val jdbc = RecordingJdbc()
        jdbc.maxAge = 1_209_600L
        val tokens = RefreshTokens(FixedRandom())
        val store = JdbcAuthSessionStore(jdbc, POLICY, tokens)

        val issued = store.create(7)

        assertTrue(RefreshTokens.wellFormed(issued.value))
        assertEquals(1_209_600L, issued.maxAgeSeconds)
        assertEquals(
            listOf(
                JdbcAuthSessionStore.LOCK_USER,
                JdbcAuthSessionStore.INSERT_SESSION,
                JdbcAuthSessionStore.REVOKE_OLDEST_ACTIVE,
            ),
            jdbc.statements,
        )
        assertArrayEquals(arrayOf<Any?>(7L), jdbc.arguments[0])
        assertArrayEquals(
            arrayOf<Any?>(7L, RefreshTokens.hash(issued.value!!), 1_209_600_000L, 2_592_000L, 2_592_000L),
            jdbc.arguments[1],
        )
        assertArrayEquals(arrayOf<Any?>(7L, 5), jdbc.arguments[2])
        for (arguments in jdbc.arguments) {
            for (argument in arguments) {
                assertFalse(issued.value == argument, "The raw refresh token must never reach SQL")
            }
        }
    }

    @Test
    fun given__the_sixth_login__when__creating__then__revoke_active_sessions_beyond_the_five_most_recently_used() {
        val sql = JdbcAuthSessionStore.REVOKE_OLDEST_ACTIVE

        assertTrue(sql.contains("SET revoked_at = CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("WHERE user_id = ?"))
        assertTrue(sql.contains("AND revoked_at IS NULL"))
        assertTrue(sql.contains("AND expires_at > CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("AND absolute_expires_at > CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("ORDER BY last_used_at DESC, session_id DESC"))
        assertTrue(sql.contains("OFFSET ?"))
    }

    @Test
    fun given__a_deleted_user__when__creating__then__fail_before_inserting_a_session() {
        val jdbc = RecordingJdbc()
        jdbc.userPresent = false
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens(FixedRandom()))

        assertThrows(IncorrectResultSizeDataAccessException::class.java) { store.create(7) }

        assertEquals(listOf(JdbcAuthSessionStore.LOCK_USER), jdbc.statements)
    }

    @Test
    fun given__a_presented_hash__when__rotating__then__move_an_active_session_to_the_next_hash_and_record_the_retired_one() {
        val jdbc = RecordingJdbc()
        jdbc.rotationRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        val rotation = store.rotate(PRESENTED_HASH, NEXT_HASH).orElseThrow()

        assertEquals(31L, rotation.sessionId)
        assertEquals(7L, rotation.userId)
        assertEquals(1_209_600L, rotation.maxAgeSeconds)
        assertEquals(
            listOf(JdbcAuthSessionStore.ROTATE, JdbcAuthSessionStore.RECORD_RETIRED),
            jdbc.statements,
        )
        assertArrayEquals(arrayOf<Any?>(NEXT_HASH, 1_209_600_000L, PRESENTED_HASH), jdbc.arguments[0])
        assertArrayEquals(arrayOf<Any?>(PRESENTED_HASH, 31L), jdbc.arguments[1])
        val sql = JdbcAuthSessionStore.ROTATE
        assertTrue(sql.contains("rotated_at = CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("last_used_at = CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("expires_at = LEAST(CURRENT_TIMESTAMP + ? * INTERVAL '1 millisecond', absolute_expires_at)"))
        assertTrue(sql.contains("WHERE token_hash = ?"))
        assertTrue(sql.contains("AND revoked_at IS NULL"))
        assertTrue(sql.contains("AND expires_at > CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("AND absolute_expires_at > CURRENT_TIMESTAMP"))
        assertTrue(JdbcAuthSessionStore.RECORD_RETIRED.contains("VALUES (?, ?, CURRENT_TIMESTAMP)"))
    }

    @Test
    fun given__no_matching_active_session__when__rotating__then__return_empty_without_recording_history() {
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        assertTrue(store.rotate(PRESENTED_HASH, NEXT_HASH).isEmpty)

        assertEquals(listOf(JdbcAuthSessionStore.ROTATE), jdbc.statements)
    }

    @Test
    fun given__a_retired_hash__when__looking_it_up__then__bind_the_sixty_second_grace_window_and_map_the_flags() {
        val jdbc = RecordingJdbc()
        jdbc.retiredRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        val retired = store.findRetired(PRESENTED_HASH).orElseThrow()

        assertEquals(31L, retired.sessionId)
        assertEquals(7L, retired.userId)
        assertTrue(retired.withinGrace)
        assertTrue(retired.latestRetirement)
        assertFalse(retired.active)
        assertArrayEquals(arrayOf<Any?>(60L, PRESENTED_HASH), jdbc.arguments.first())
        val sql = JdbcAuthSessionStore.FIND_RETIRED
        assertTrue(sql.contains("history.rotated_at > CURRENT_TIMESTAMP - ? * INTERVAL '1 second' AS within_grace"))
        assertTrue(sql.contains("session.rotated_at = history.rotated_at AS latest_retirement"))
    }

    @Test
    fun given__an_unused_successor__when__recovering_a_lost_response__then__lock_it_and_retire_it_through_a_normal_rotation() {
        val jdbc = RecordingJdbc()
        jdbc.successorRows = 1
        jdbc.rotationRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        val rotation = store.rotateUnusedSuccessor(31, PRESENTED_HASH, NEXT_HASH).orElseThrow()

        assertEquals(31L, rotation.sessionId)
        assertEquals(
            listOf(
                JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR,
                JdbcAuthSessionStore.ROTATE,
                JdbcAuthSessionStore.RECORD_RETIRED,
            ),
            jdbc.statements,
        )
        assertArrayEquals(arrayOf<Any?>(31L, PRESENTED_HASH), jdbc.arguments[0])
        assertArrayEquals(arrayOf<Any?>(NEXT_HASH, 1_209_600_000L, SUCCESSOR_HASH), jdbc.arguments[1])
        assertArrayEquals(arrayOf<Any?>(SUCCESSOR_HASH, 31L), jdbc.arguments[2])
        val lock = JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR
        assertTrue(lock.contains("AND last_used_at = rotated_at"))
        assertTrue(lock.contains("AND rotated_at = (SELECT rotated_at FROM pawbridge_user.auth_session_token_history WHERE token_hash = ?)"))
        assertTrue(lock.contains("FOR UPDATE"))
    }

    @Test
    fun given__the_session_already_moved_on__when__recovering_a_lost_response__then__return_empty_without_writing() {
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        assertTrue(store.rotateUnusedSuccessor(31, PRESENTED_HASH, NEXT_HASH).isEmpty)

        assertEquals(listOf(JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR), jdbc.statements)
    }

    @Test
    fun given__revocations_and_cleanup__when__writing__then__use_the_expected_statements_and_bindings() {
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        store.revoke(31)
        store.revokeByTokenHash(PRESENTED_HASH)
        store.revokeAll(7)
        store.deleteFinished(1000)

        assertEquals(
            listOf(
                JdbcAuthSessionStore.REVOKE_SESSION,
                JdbcAuthSessionStore.REVOKE_BY_TOKEN,
                JdbcAuthSessionStore.REVOKE_ALL_FOR_USER,
                JdbcAuthSessionStore.DELETE_FINISHED,
            ),
            jdbc.statements,
        )
        assertArrayEquals(arrayOf<Any?>(31L), jdbc.arguments[0])
        assertArrayEquals(arrayOf<Any?>(PRESENTED_HASH), jdbc.arguments[1])
        assertArrayEquals(arrayOf<Any?>(7L), jdbc.arguments[2])
        assertArrayEquals(arrayOf<Any?>(1000), jdbc.arguments[3])
        val cleanup = JdbcAuthSessionStore.DELETE_FINISHED
        assertTrue(cleanup.contains("expires_at <= CURRENT_TIMESTAMP"))
        assertTrue(cleanup.contains("absolute_expires_at <= CURRENT_TIMESTAMP"))
        assertTrue(cleanup.contains("revoked_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'"))
    }

    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = (index * 7 + 3).toByte()
            }
        }
    }

    private class RecordingJdbc : JdbcTemplate() {
        val statements = ArrayList<String>()
        val arguments = ArrayList<Array<out Any?>>()
        var userPresent = true
        var maxAge = 0L
        var rotationRows = 0
        var retiredRows = 0
        var successorRows = 0

        @Suppress("UNCHECKED_CAST")
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            statements.add(sql)
            arguments.add(args)
            val rows = ArrayList<T>()
            try {
                val row = mock(ResultSet::class.java)
                var count = 0
                if (sql == JdbcAuthSessionStore.LOCK_USER) {
                    `when`(row.getLong("user_id")).thenReturn(7L)
                    count = if (userPresent) 1 else 0
                } else if (sql == JdbcAuthSessionStore.INSERT_SESSION) {
                    `when`(row.getLong("max_age_seconds")).thenReturn(maxAge)
                    count = 1
                } else if (sql == JdbcAuthSessionStore.ROTATE) {
                    `when`(row.getLong("session_id")).thenReturn(31L)
                    `when`(row.getLong("user_id")).thenReturn(7L)
                    `when`(row.getLong("max_age_seconds")).thenReturn(1_209_600L)
                    count = rotationRows
                } else if (sql == JdbcAuthSessionStore.FIND_RETIRED) {
                    `when`(row.getLong("session_id")).thenReturn(31L)
                    `when`(row.getLong("user_id")).thenReturn(7L)
                    `when`(row.getBoolean("within_grace")).thenReturn(true)
                    `when`(row.getBoolean("latest_retirement")).thenReturn(true)
                    `when`(row.getBoolean("active")).thenReturn(false)
                    count = retiredRows
                } else if (sql == JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR) {
                    `when`(row.getString("token_hash")).thenReturn(SUCCESSOR_HASH)
                    count = successorRows
                } else {
                    throw AssertionError("Unexpected query")
                }
                for (index in 0 until count) {
                    rows.add(rowMapper.mapRow(row, index) as T)
                }
                return rows
            } catch (failure: SQLException) {
                throw AssertionError(failure)
            }
        }

        override fun update(sql: String, vararg args: Any?): Int {
            statements.add(sql)
            arguments.add(args)
            return 1
        }
    }

    companion object {
        private val POLICY = AuthSessionPolicy(
            Duration.ofMillis(1_209_600_000L),
            Duration.ofDays(30),
            5,
            Duration.ofSeconds(60),
        )
        private val PRESENTED_HASH = "a".repeat(64)
        private val NEXT_HASH = "b".repeat(64)
        private val SUCCESSOR_HASH = "c".repeat(64)
    }
}
