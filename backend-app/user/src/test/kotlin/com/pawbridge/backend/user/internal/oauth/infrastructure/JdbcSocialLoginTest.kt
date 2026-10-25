package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin.SocialUser
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
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
    fun given__unsupported_provider_or_missing_email__when__loading__then__reject_before_any_account_transaction(
        example: String,
    ) {
        val f = Fixture()
        val attributes = HashMap<String, Any?>(ATTRIBUTES)
        if (example == "missing-email") {
            attributes.remove("email")
        }
        if (example == "empty-email") {
            attributes["email"] = ""
        }

        assertThrows(SocialLoginRejectedException::class.java) {
            f.login.load(if (example == "unsupported") "other" else "google", attributes)
        }

        assertTrue(f.events.isEmpty())
        assertTrue(f.jdbc.bindings.isEmpty())
        assertEquals(0, f.nicknames)
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__existing_google_user__when__loading_again__then__preserve_stored_profile_role_and_skip_local_lookup_nickname_or_update() {
        val f = Fixture()
        f.jdbc.google = listOf(EXISTING)

        assertEquals(EXISTING, f.login.load("GOOGLE", ATTRIBUTES))

        assertEquals(listOf("begin", "user:GOOGLE", "commit", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertTrue(f.jdbc.bindings.isEmpty())
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__local_account_with_same_email__when__loading_google__then__reject_without_linking_or_inserting() {
        val f = Fixture()
        f.jdbc.local = listOf(EXISTING)

        assertEquals(
            "이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.",
            assertThrows(SocialLoginRejectedException::class.java) { f.login.load("google", ATTRIBUTES) }.message,
        )

        assertEquals(listOf("begin", "user:GOOGLE", "user:LOCAL", "rollback", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertTrue(f.jdbc.bindings.isEmpty())
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__new_google_account__when__loading__then__insert_source_defaults_provider_id_and_one_audit_time_without_issuing_tokens() {
        val f = Fixture()

        val user = f.login.load("google", ATTRIBUTES)

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
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__integrity_failure_on_both_attempts__when__creating__then__rollback_and_cleanup_each_transaction_and_propagate_without_a_third_attempt() {
        val f = Fixture()
        val failure = DataIntegrityViolationException("synthetic unique")
        f.jdbc.userFailure = failure

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.login.load("google", ATTRIBUTES) })

        assertEquals(2, f.nicknames)
        assertEquals(
            listOf(
                "begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "rollback", "cleanup",
                "begin", "user:GOOGLE", "user:LOCAL", "nickname", "insert-user", "rollback", "cleanup",
            ),
            f.events,
        )
        verifyNoInteractions(f.sessions)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__first_integrity_failure__when__retrying__then__finish_rollback_before_reloading_and_reuse_a_raced_account_or_generate_one_more_nickname(
        racedAccount: Boolean,
    ) {
        val f = Fixture()
        f.jdbc.userFailure = DataIntegrityViolationException("synthetic unique")
        f.jdbc.userFailuresRemaining = 1
        if (racedAccount) {
            f.jdbc.googleAfterFailure = EXISTING
        }

        val result = f.login.load("google", ATTRIBUTES)

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
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__existing_social_user__when__completing_login__then__create_one_session_inside_a_separate_transaction() {
        val f = Fixture()
        val issued = IssuedRefreshToken("synthetic-session-token", 1_209_600)
        `when`(f.sessions.create(7L)).thenAnswer {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            f.events.add("create-session")
            issued
        }

        assertSame(issued, f.login.complete(EXISTING))

        assertEquals(listOf("begin", "create-session", "commit", "cleanup"), f.events)
        verify(f.sessions).create(7L)
        verifyNoMoreInteractions(f.sessions)
        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__session_write_or_commit_failure__when__completing_login__then__propagate_without_retry_or_success_result(
        commit: Boolean,
    ) {
        val f = Fixture()
        val failure: RuntimeException = if (commit) {
            TransactionSystemException("synthetic commit")
        } else {
            DataAccessResourceFailureException("synthetic session")
        }
        if (commit) {
            f.manager.failure = failure
            `when`(f.sessions.create(7L)).thenReturn(IssuedRefreshToken("synthetic-session-token", 1))
        } else {
            `when`(f.sessions.create(7L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.login.complete(EXISTING) })

        assertEquals(listOf("begin", if (commit) "commit" else "rollback", "cleanup"), f.events)
        verify(f.sessions).create(7L)
    }

    @Test
    fun given__multiple_rows_where_source_requires_optional_one__when__loading__then__do_not_choose_an_arbitrary_row() {
        val f = Fixture()
        f.jdbc.google = listOf(EXISTING, EXISTING)

        assertThrows(IncorrectResultSizeDataAccessException::class.java) { f.login.load("google", ATTRIBUTES) }

        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @Test
    fun given__new_naver_account__when__loading__then__look_up_by_provider_id_and_insert_naver_provider_and_id() {
        val f = Fixture()

        val user = f.login.load("naver", NAVER_ATTRIBUTES)

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
        verifyNoInteractions(f.sessions)
    }

    @Test
    fun given__existing_naver_provider_id__when__loading__then__return_stored_account_without_email_checks_or_insert() {
        val f = Fixture()
        f.jdbc.naverById = listOf(NAVER_EXISTING)

        assertEquals(NAVER_EXISTING, f.login.load("naver", NAVER_ATTRIBUTES))

        assertEquals(listOf("begin", "user-id:NAVER", "commit", "cleanup"), f.events)
        assertEquals(0, f.nicknames)
        assertEquals(0, f.jdbc.writes)
    }

    @Test
    fun given__local_account_with_same_email__when__loading_naver__then__reject_without_linking() {
        val f = Fixture()
        f.jdbc.local = listOf(EXISTING)

        val rejected = assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", NAVER_ATTRIBUTES) }

        assertEquals("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.", rejected.message)
        assertEquals(listOf("begin", "user-id:NAVER", "user:LOCAL", "rollback", "cleanup"), f.events)
        assertEquals(0, f.jdbc.writes)
    }

    @Test
    fun given__naver_account_with_same_email_but_other_provider_id__when__loading__then__reject_instead_of_relinking_by_email() {
        val f = Fixture()
        f.jdbc.naverByEmail = listOf(NAVER_EXISTING)

        val rejected = assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", NAVER_ATTRIBUTES) }

        assertEquals("이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.", rejected.message)
        assertEquals(listOf("begin", "user-id:NAVER", "user:LOCAL", "user:NAVER", "rollback", "cleanup"), f.events)
        assertEquals(0, f.jdbc.writes)
        assertEquals(0, f.nicknames)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-response", "missing-id", "missing-email"])
    fun given__incomplete_naver_profile__when__loading__then__reject_before_any_account_transaction(example: String) {
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

        assertThrows(SocialLoginRejectedException::class.java) { f.login.load("naver", attributes) }

        assertTrue(f.events.isEmpty())
        assertEquals(0, f.nicknames)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val jdbc = RecordingJdbc(events)
        val manager = Manager(events)
        val sessions: JdbcAuthSessionStore = mock(JdbcAuthSessionStore::class.java)
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
            val row = mock(ResultSet::class.java)
            `when`(row.getLong("user_id")).thenReturn(user.userId)
            `when`(row.getString("email")).thenReturn(user.email)
            `when`(row.getString("name")).thenReturn(user.name)
            `when`(row.getString("role")).thenReturn(user.role)
            `when`(row.getString("care_reg_no")).thenReturn(user.careRegNo)
            `when`(row.getString("provider")).thenReturn(user.provider)
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
            val connection = mock(Connection::class.java)
            val statement = mock(PreparedStatement::class.java)
            try {
                `when`(connection.prepareStatement(anyString(), any(Array<String>::class.java))).thenAnswer { call ->
                    assertTrue((call.getArgument<Any>(0) as String).contains("INSERT INTO pawbridge_user.users"))
                    assertArrayEquals(arrayOf("user_id"), call.getArgument<Any>(1) as Array<String>)
                    statement
                }
                doAnswer { call ->
                    values[call.getArgument<Int>(0)] = call.getArgument<Any?>(1)
                    null
                }.`when`(statement).setString(anyInt(), nullable(String::class.java))
                doAnswer { call ->
                    values[call.getArgument<Int>(0)] = call.getArgument<Any?>(1)
                    null
                }.`when`(statement).setLong(anyInt(), anyLong())
                doAnswer { call ->
                    values[call.getArgument<Int>(0)] = call.getArgument<Any?>(1)
                    calendarZones.add((call.getArgument<Any>(2) as Calendar).timeZone.id)
                    null
                }.`when`(statement).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
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
