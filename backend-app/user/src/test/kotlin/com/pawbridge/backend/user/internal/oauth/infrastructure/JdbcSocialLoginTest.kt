package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin.SocialUser
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.KeyHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.function.Supplier

class JdbcSocialLoginTest {
    @ParameterizedTest
    @ValueSource(strings = ["unsupported", "missing-email", "empty-email"])
    fun `지원하지 않는 제공자이거나 이메일이 없을 때__불러오면__계정 트랜잭션 전에 거부한다`(
        example: String,
    ) {
        // given
        val f = Fixture()
        val attributes = HashMap<String, Any?>(ATTRIBUTES)
        if (example == "missing-email") {
            attributes.remove("email")
        }
        if (example == "empty-email") {
            attributes["email"] = ""
        }

        // when
        assertThrows(SocialLoginRejectedException::class.java) {
            f.login.load(if (example == "unsupported") "other" else "google", attributes)
        }

        // then
        assertTrue(f.events.isEmpty())
        assertTrue(f.jdbc.bindings.isEmpty())
        assertEquals(0, f.nicknames)
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `이미 있는 구글 회원일 때__다시 불러오면__저장된 프로필과 역할을 보존하고 로컬 조회와 닉네임과 갱신을 건너뛴다`() {
        // given
        val f = Fixture()
        f.jdbc.google = listOf(EXISTING)

        // when
        val user = f.login.load("GOOGLE", ATTRIBUTES)

        // then
        assertEquals(EXISTING, user)
        assertEquals(listOf("begin", "user:GOOGLE", "commit", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertTrue(f.jdbc.bindings.isEmpty())
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `같은 이메일의 로컬 계정이 있을 때__구글로 불러오면__연결이나 삽입 없이 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.local = listOf(EXISTING)

        // when
        val rejected = assertThrows(SocialLoginRejectedException::class.java) { f.login.load("google", ATTRIBUTES) }

        // then
        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.", rejected.message)
        assertEquals(listOf("begin", "user:GOOGLE", "user:LOCAL", "rollback", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertTrue(f.jdbc.bindings.isEmpty())
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `새 구글 계정일 때__불러오면__원본 기본값과 제공자 id와 하나의 감사 시각으로 삽입하고 토큰은 발급하지 않는다`() {
        // given
        val f = Fixture()

        // when
        val user = f.login.load("google", ATTRIBUTES)

        // then
        assertEquals(SocialUser(19, "social@example.test", "Social", "ROLE_USER", null, "GOOGLE"), user)
        assertEquals(
            listOf("begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "commit", "cleanup"),
            f.events,
        )
        val values: Map<Int, Any?> = f.jdbc.bindings.first()
        mapOf<Int, Any?>(
            1 to "social@example.test",
            2 to "Social",
            3 to "nickname1",
            4 to "OAuth2",
            5 to "ROLE_USER",
            7 to "GOOGLE",
            8 to "provider-sub",
            9 to Timestamp.valueOf(NOW),
            10 to Timestamp.valueOf(NOW),
        ).forEach { (key, value) -> assertEquals(value, values[key]) }
        assertTrue(values.containsKey(6))
        assertNull(values[6])
        assertEquals(listOf("UTC", "UTC"), f.jdbc.calendarZones)
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `두 번 모두 무결성 실패가 났을 때__생성하면__각 트랜잭션을 롤백하고 정리한 뒤 세 번째 시도 없이 전파한다`() {
        // given
        val f = Fixture()
        val failure = DataIntegrityViolationException("synthetic unique")
        f.jdbc.userFailure = failure

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.login.load("google", ATTRIBUTES) }

        // then
        assertSame(failure, thrown)
        assertEquals(2, f.nicknames)
        assertEquals(
            listOf(
                "begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "rollback", "cleanup",
                "begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "rollback", "cleanup",
            ),
            f.events,
        )
        verify { f.sessions wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `첫 무결성 실패가 났을 때__재시도하면__롤백을 끝낸 뒤 다시 조회해 경합으로 생긴 계정을 재사용하거나 닉네임을 하나 더 생성한다`(
        racedAccount: Boolean,
    ) {
        // given
        val f = Fixture()
        f.jdbc.userFailure = DataIntegrityViolationException("synthetic unique")
        f.jdbc.userFailuresRemaining = 1
        if (racedAccount) {
            f.jdbc.googleAfterFailure = EXISTING
        }


        // when
        val result = f.login.load("google", ATTRIBUTES)

        // then
        assertEquals(
            if (racedAccount) EXISTING else SocialUser(19, "social@example.test", "Social", "ROLE_USER", null, "GOOGLE"),
            result,
        )
        val expected = ArrayList(
            listOf("begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "rollback", "cleanup", "begin", "user:GOOGLE"),
        )
        if (!racedAccount) {
            expected.addAll(listOf("user:LOCAL", "nickname", "insert-user"))
        }
        expected.addAll(listOf("commit", "cleanup"))
        assertEquals(expected, f.events)
        assertEquals(if (racedAccount) 1 else 2, f.nicknames)
        assertEquals(if (racedAccount) 1 else 2, f.jdbc.writes)
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `이미 있는 소셜 회원일 때__로그인을 마무리하면__별도 트랜잭션에서 세션을 하나 만든다`() {
        // given
        val f = Fixture()
        val issued = IssuedRefreshToken("synthetic-session-token", 1_209_600)

        every { f.sessions.create(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            f.events.add("create-session")
            issued
        }

        // when
        val result = f.login.complete(EXISTING)

        // then
        assertSame(issued, result)
        assertEquals(listOf("begin", "create-session", "commit", "cleanup"), f.events)
        verify(exactly = 1) { f.sessions.create(7L) }
        confirmVerified(f.sessions)
        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `세션 쓰기나 커밋이 실패했을 때__로그인을 마무리하면__재시도나 성공 결과 없이 전파한다`(
        commit: Boolean,
    ) {
        // given
        val f = Fixture()
        val failure: RuntimeException = if (commit) {
            TransactionSystemException("synthetic commit")
        } else {
            DataAccessResourceFailureException("synthetic session")
        }

        if (commit) {
            f.manager.failure = failure
            every { f.sessions.create(any()) } returns IssuedRefreshToken("synthetic-session-token", 1)
        } else {
            every { f.sessions.create(any()) } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.login.complete(EXISTING) }

        // then
        assertSame(failure, thrown)
        assertEquals(listOf("begin", if (commit) "commit" else "rollback", "cleanup"), f.events)
        verify(exactly = 1) { f.sessions.create(7L) }
    }

    @Test
    fun `원본이 옵셔널 하나를 요구하는데 행이 여러 개일 때__불러오면__임의의 행을 고르지 않는다`() {
        // given
        val f = Fixture()
        f.jdbc.google = listOf(EXISTING, EXISTING)

        // when
        assertThrows(IncorrectResultSizeDataAccessException::class.java) { f.login.load("google", ATTRIBUTES) }

        // then
        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @Test
    fun `새 네이버 계정일 때__불러오면__제공자 id로 조회하고 네이버 제공자와 id를 삽입한다`() {
        // given
        val f = Fixture()

        // when
        val user = f.login.load("naver", NAVER_ATTRIBUTES)

        // then
        assertEquals(SocialUser(19, "social@example.test", "Naver", "ROLE_USER", null, "NAVER"), user)
        assertEquals(
            listOf("begin", "user-id:NAVER", "user:LOCAL", "user:NAVER", "nickname", "insert-user", "commit", "cleanup"),
            f.events,
        )
        val values: Map<Int, Any?> = f.jdbc.bindings.first()
        assertEquals("social@example.test", values[1])
        assertEquals("Naver", values[2])
        assertEquals("NAVER", values[7])
        assertEquals("naver-user-id", values[8])
        verify { f.sessions wasNot Called }
    }

    @Test
    fun `이미 있는 네이버 제공자 id일 때__불러오면__이메일 확인이나 삽입 없이 저장된 계정을 돌려준다`() {
        // given
        val f = Fixture()
        f.jdbc.naverById = listOf(NAVER_EXISTING)

        // when
        val user = f.login.load("naver", NAVER_ATTRIBUTES)

        // then
        assertEquals(NAVER_EXISTING, user)
        assertEquals(listOf("begin", "user-id:NAVER", "commit", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertEquals(0, f.jdbc.writes)
    }

    @Test
    fun `같은 이메일의 로컬 계정이 있을 때__네이버로 불러오면__연결 없이 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.local = listOf(EXISTING)

        // when
        val rejected = assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", NAVER_ATTRIBUTES) }

        // then
        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.", rejected.message)
        assertEquals(listOf("begin", "user-id:NAVER", "user:LOCAL", "rollback", "cleanup"), f.events)
        assertEquals(0, f.jdbc.writes)
    }

    @Test
    fun `같은 이메일이지만 다른 제공자 id의 네이버 계정일 때__불러오면__이메일로 다시 연결하지 않고 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.naverByEmail = listOf(NAVER_EXISTING)

        // when
        val rejected = assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", NAVER_ATTRIBUTES) }

        // then
        assertEquals("이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.", rejected.message)
        assertEquals(listOf("begin", "user-id:NAVER", "user:LOCAL", "user:NAVER", "rollback", "cleanup"), f.events)
        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-response", "missing-id", "missing-email"])
    fun `불완전한 네이버 프로필일 때__불러오면__계정 트랜잭션 전에 거부한다`(example: String) {
        // given
        val f = Fixture()
        val response = HashMap<String, Any?>(
            java.util.Map.of<String, Any?>("id", "naver-user-id", "email", "social@example.test", "name", "Naver"),
        )
        if (example == "missing-id") {
            response.remove("id")
        }
        if (example == "missing-email") {
            response.remove("email")
        }
        val attributes: MutableMap<String, Any?> = if (example == "missing-response") {
            java.util.Map.of<String, Any?>("resultcode", "00")
        } else {
            java.util.Map.of<String, Any?>("response", response)
        }

        // when
        assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", attributes) }

        // then
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.nicknames)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val jdbc = RecordingJdbc(events)
        val manager = Manager(events)
        val sessions = mockk<JdbcAuthSessionStore>()
        val login: JdbcSocialLogin
        var nicknames = 0

        init {
            val nickname = Supplier {
                events.add("nickname")
                "nickname" + (++nicknames)
            }
            login = JdbcSocialLogin(jdbc, TransactionTemplate(manager), sessions, nickname, Supplier { NOW })
        }
    }

    private class RecordingJdbc(private val events: MutableList<String>) : JdbcTemplate() {
        var google: List<SocialUser> = listOf()
        var local: List<SocialUser> = listOf()
        var naverById: List<SocialUser> = listOf()
        var naverByEmail: List<SocialUser> = listOf()
        val bindings = ArrayList<MutableMap<Int, Any?>>()
        val calendarZones = ArrayList<String>()
        var userFailure: RuntimeException? = null
        var writes = 0
        var userFailuresRemaining = Int.MAX_VALUE
        var googleAfterFailure: SocialUser? = null

        private fun row(user: SocialUser): ResultSet {
            val row = mockk<ResultSet>()
            every { row.getLong("user_id") } returns user.userId
            every { row.getString("email") } returns user.email
            every { row.getString("name") } returns user.name
            every { row.getString("role") } returns user.role
            every { row.getString("care_reg_no") } returns user.careRegNo
            every { row.getString("provider") } returns user.provider
            return row
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            val result = ArrayList<T>()
            try {
                if (sql.contains("provider_id = ?")) {
                    events.add("user-id:" + args[0])
                    assertEquals("naver-user-id", args[1])
                    for (user in naverById) {
                        result.add(rowMapper.mapRow(row(user), 0) as T)
                    }
                } else if (sql.startsWith("SELECT user_id")) {
                    events.add("user:" + args[1])
                    val users = when (args[1] as String) {
                        "GOOGLE" -> google
                        "NAVER" -> naverByEmail
                        else -> local
                    }
                    for (user in users) {
                        result.add(rowMapper.mapRow(row(user), 0) as T)
                    }
                } else {
                    throw AssertionError("Unexpected query")
                }
                return result
            } catch (failure: SQLException) {
                throw IllegalStateException(failure)
            }
        }

        override fun update(psc: PreparedStatementCreator, generatedKeyHolder: KeyHolder): Int {
            writes++
            events.add("insert-user")
            if (userFailure != null && userFailuresRemaining-- > 0) {
                if (googleAfterFailure != null) {
                    google = listOf(googleAfterFailure!!)
                }
                throw userFailure!!
            }
            bind(psc)
            generatedKeyHolder.keyList.add(mutableMapOf<String, Any>("user_id" to 19L))
            return 1
        }

        private fun bind(creator: PreparedStatementCreator) {
            val values = HashMap<Int, Any?>()
            bindings.add(values)
            val connection = mockk<Connection>()
            val statement = mockk<PreparedStatement>()
            try {
                every { connection.prepareStatement(any<String>(), any<Array<String>>()) } answers {
                    assertTrue(firstArg<String>().contains("INSERT INTO pawbridge_user.users"))
                    assertArrayEquals(arrayOf("user_id"), secondArg<Array<String>>())
                    statement
                }
                every { statement.setString(any(), any()) } answers {
                    values[firstArg<Int>()] = secondArg<String?>()
                }
                every { statement.setLong(any(), any()) } answers {
                    values[firstArg<Int>()] = secondArg<Long>()
                }
                every { statement.setTimestamp(any(), any(), any<Calendar>()) } answers {
                    values[firstArg<Int>()] = secondArg<Timestamp>()
                    calendarZones += thirdArg<Calendar>().timeZone.id
                }
                assertSame(statement, creator.createPreparedStatement(connection))
            } catch (failure: SQLException) {
                throw IllegalStateException(failure)
            }
        }
    }

    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var failure: RuntimeException? = null

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            if (failure != null) {
                throw failure!!
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }

        override fun doCleanupAfterCompletion(transaction: Any) {
            events.add("cleanup")
        }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.parse("2030-01-01T12:34:56.123456")
        private val ATTRIBUTES: MutableMap<String, Any?> =
            java.util.Map.of<String, Any?>("email", "social@example.test", "name", "Social", "sub", "provider-sub")
        private val EXISTING = SocialUser(7, "social@example.test", "Stored", "ROLE_ADMIN", "care", "GOOGLE")
        private val NAVER_ATTRIBUTES: MutableMap<String, Any?> = java.util.Map.of<String, Any?>(
            "resultcode", "00",
            "message", "success",
            "response", java.util.Map.of<String, Any?>("id", "naver-user-id", "email", "social@example.test", "name", "Naver"),
        )
        private val NAVER_EXISTING = SocialUser(8, "social@example.test", "Stored", "ROLE_USER", null, "NAVER")
    }
}
