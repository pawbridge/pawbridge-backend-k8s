package com.pawbridge.backend.user.internal.session.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Duration

/**
 * Statement and binding contract. Row semantics (expiry, races, eviction order) are exercised against PostgreSQL
 * in PostgresqlAuthSessionCompatibilityTest.
 */
class JdbcAuthSessionStoreTest {
    @Test
    fun `로그인할 때__세션을 만들면__사용자를 잠그고 해시만 저장하며 최근 세션 다섯 개만 유지한다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.maxAge = 1_209_600L
        val tokens = RefreshTokens(FixedRandom())
        val store = JdbcAuthSessionStore(jdbc, POLICY, tokens)

        // when
        val issued = store.create(7)

        // then
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
    fun `여섯 번째 로그인일 때__세션을 만들면__최근 사용한 다섯 개를 넘는 활성 세션을 폐기하는 SQL을 쓴다`() {
        // when
        val sql = JdbcAuthSessionStore.REVOKE_OLDEST_ACTIVE

        // then
        assertTrue(sql.contains("SET revoked_at = CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("WHERE user_id = ?"))
        assertTrue(sql.contains("AND revoked_at IS NULL"))
        assertTrue(sql.contains("AND expires_at > CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("AND absolute_expires_at > CURRENT_TIMESTAMP"))
        assertTrue(sql.contains("ORDER BY last_used_at DESC, session_id DESC"))
        assertTrue(sql.contains("OFFSET ?"))
    }

    @Test
    fun `삭제된 사용자일 때__세션을 만들면__세션을 삽입하기 전에 실패한다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.userPresent = false
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens(FixedRandom()))

        // when
        assertThrows(IncorrectResultSizeDataAccessException::class.java) { store.create(7) }

        // then
        assertEquals(listOf(JdbcAuthSessionStore.LOCK_USER), jdbc.statements)
    }

    @Test
    fun `제시된 해시가 있을 때__회전하면__활성 세션을 다음 해시로 옮기고 폐기된 해시를 기록한다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.rotationRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        val rotation = store.rotate(PRESENTED_HASH, NEXT_HASH).orElseThrow()

        // then
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
    fun `일치하는 활성 세션이 없을 때__회전하면__이력을 기록하지 않고 빈 값을 돌려준다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        val rotation = store.rotate(PRESENTED_HASH, NEXT_HASH)

        // then
        assertTrue(rotation.isEmpty)
        assertEquals(listOf(JdbcAuthSessionStore.ROTATE), jdbc.statements)
    }

    @Test
    fun `폐기된 해시가 있을 때__조회하면__60초 유예 구간을 바인딩하고 플래그를 그대로 매핑한다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.retiredRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        val retired = store.findRetired(PRESENTED_HASH).orElseThrow()

        // then
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
    fun `후속 토큰이 쓰이지 않았을 때__잃어버린 응답을 복구하면__후속 토큰을 잠그고 일반 회전으로 폐기한다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.successorRows = 1
        jdbc.rotationRows = 1
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        val rotation = store.rotateUnusedSuccessor(31, PRESENTED_HASH, NEXT_HASH).orElseThrow()

        // then
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
    fun `세션이 이미 다음 토큰으로 넘어갔을 때__잃어버린 응답을 복구하면__쓰기 없이 빈 값을 돌려준다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        val rotation = store.rotateUnusedSuccessor(31, PRESENTED_HASH, NEXT_HASH)

        // then
        assertTrue(rotation.isEmpty)
        assertEquals(listOf(JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR), jdbc.statements)
    }

    @Test
    fun `폐기와 정리를 요청할 때__쓰면__예상한 SQL과 바인딩을 사용한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcAuthSessionStore(jdbc, POLICY, RefreshTokens())

        // when
        store.revoke(31)
        store.revokeByTokenHash(PRESENTED_HASH)
        store.revokeAll(7)
        store.deleteFinished(1000)

        // then
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
            val values: Map<String, Any?>
            val count: Int
            if (sql == JdbcAuthSessionStore.LOCK_USER) {
                values = mapOf("user_id" to 7L)
                count = if (userPresent) 1 else 0
            } else if (sql == JdbcAuthSessionStore.INSERT_SESSION) {
                values = mapOf("max_age_seconds" to maxAge)
                count = 1
            } else if (sql == JdbcAuthSessionStore.ROTATE) {
                values = mapOf("session_id" to 31L, "user_id" to 7L, "max_age_seconds" to 1_209_600L)
                count = rotationRows
            } else if (sql == JdbcAuthSessionStore.FIND_RETIRED) {
                values = mapOf(
                    "session_id" to 31L,
                    "user_id" to 7L,
                    "within_grace" to true,
                    "latest_retirement" to true,
                    "active" to false,
                )
                count = retiredRows
            } else if (sql == JdbcAuthSessionStore.LOCK_UNUSED_SUCCESSOR) {
                values = mapOf("token_hash" to SUCCESSOR_HASH)
                count = successorRows
            } else {
                throw AssertionError("Unexpected query")
            }
            val row = resultSet(values)
            for (index in 0 until count) {
                rows.add(rowMapper.mapRow(row, index) as T)
            }
            return rows
        }

        override fun update(sql: String, vararg args: Any?): Int {
            statements.add(sql)
            arguments.add(args)
            return 1
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
