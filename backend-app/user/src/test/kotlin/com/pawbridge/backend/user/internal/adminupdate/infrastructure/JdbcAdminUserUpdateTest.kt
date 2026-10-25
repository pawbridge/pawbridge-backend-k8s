package com.pawbridge.backend.user.internal.adminupdate.infrastructure

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
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
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.function.Supplier
import java.util.stream.Stream

private fun normalized(sql: String): String {
    return sql.replace(Regex("\\s+"), " ").trim()
}

class JdbcAdminUserUpdateTest {
    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 8])
    fun given__missing_target_including_nonpositive_id__when__locking__then__preserve_missing_user_without_a_new_positive_guard(
        id: Long,
    ) {
        val f = Fixture()
        f.jdbc.present = false

        assertThrows(UserNotFoundException::class.java) {
            f.query.update(id, Changes("Duplicate", Role.ROLE_SHELTER, "B"))
        }

        assertEquals(listOf("lock"), f.jdbc.events)
        assertArrayEquals(arrayOf<Any?>(id), f.jdbc.lockArguments)
        assertTrue(f.jdbc.lockSql!!.endsWith("WHERE user_id = ? FOR UPDATE"))
        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.samples)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @MethodSource("approvalRequests")
    fun given__approval_required_before_a_duplicate_nickname__when__updating_locked_user__then__reject_before_duplicate_or_write(
        current: Role,
        care: String?,
        request: Changes,
    ) {
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = care
        f.jdbc.duplicate = true

        assertThrows(ShelterApprovalRequiredException::class.java) { f.query.update(8, request) }

        assertEquals(listOf("lock"), f.jdbc.events)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.rollbacks)
    }

    @Test
    fun given__case_only_nickname_and_existing_self_match__when__updating__then__keep_original_duplicate_query_and_409_exception() {
        val f = Fixture()
        f.jdbc.duplicate = true

        assertThrows(NicknameDuplicateException::class.java) { f.query.update(8, Changes("ALPHA", null, null)) }

        assertEquals(listOf("lock", "duplicate"), f.jdbc.events)
        assertEquals("SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)", f.jdbc.duplicateSql)
        assertArrayEquals(arrayOf<Any?>("ALPHA"), f.jdbc.duplicateArguments)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.rollbacks)
    }

    @ParameterizedTest
    @MethodSource("noChanges")
    fun given__no_dirty_property__when__saving_equivalent_values__then__commit_without_update_or_auditing(request: Changes) {
        val f = Fixture()
        f.jdbc.care = "A"

        f.query.update(8, request)

        assertEquals(listOf("lock"), f.jdbc.events)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.commits)
        assertEquals(0, f.tx.rollbacks)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @MethodSource("validChanges")
    fun given__allowed_dirty_fields__when__updating__then__bind_only_mutable_fields_and_one_utc_audit_time_inside_the_lock_transaction(
        current: Role,
        currentCare: String?,
        request: Changes,
        nickname: String,
        nextRole: Role,
    ) {
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = currentCare

        f.query.update(8, request)

        assertEquals(
            if (request.nickname != null && request.nickname != "Alpha") {
                listOf("lock", "duplicate", "update")
            } else {
                listOf("lock", "update")
            },
            f.jdbc.events,
        )
        assertEquals(
            "UPDATE pawbridge_user.users SET nickname = ?, role = ?, care_reg_no = ?, updated_at = ? WHERE user_id = ?",
            f.jdbc.updateSql,
        )
        verify(f.jdbc.statement).setString(1, nickname)
        verify(f.jdbc.statement).setString(2, nextRole.name)
        verify(f.jdbc.statement).setString(3, currentCare)
        val zone = ArgumentCaptor.forClass(Calendar::class.java)
        verify(f.jdbc.statement).setTimestamp(eq(4), eq(Timestamp.valueOf(MODIFIED)), zone.capture())
        assertEquals("UTC", zone.value.timeZone.id)
        verify(f.jdbc.statement).setLong(5, 8)
        assertEquals(1, f.samples)
        assertEquals(1, f.tx.starts)
        assertEquals(1, f.tx.commits)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["begin", "update", "commit"])
    fun given__begin_unique_race_or_commit_failure__when__updating__then__propagate_without_duplicate_reclassification_or_retry(
        phase: String,
    ) {
        val f = Fixture()
        val failure: RuntimeException = if (phase == "update") {
            DataIntegrityViolationException("synthetic unique race")
        } else {
            TransactionSystemException("synthetic boundary")
        }
        if (phase == "begin") {
            f.tx.beginFailure = failure
        } else if (phase == "commit") {
            f.tx.commitFailure = failure
        } else {
            f.jdbc.updateFailure = failure
        }

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) { f.query.update(8, Changes("Beta", null, null)) },
        )

        assertEquals(1, f.tx.starts)
        assertEquals(if (phase == "update") 1 else 0, f.tx.rollbacks)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun given__unexpected_write_count__when__updating_locked_user__then__rollback_instead_of_reporting_success(changed: Int) {
        val f = Fixture()
        f.jdbc.changed = changed

        assertThrows(IllegalStateException::class.java) { f.query.update(8, Changes("Beta", null, null)) }

        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.tx.commits)
    }

    @Test
    fun given__prepared_statement_binding_failure__when__updating__then__close_the_statement_and_propagate_sql_failure() {
        val f = Fixture()
        val failure = SQLException("synthetic bind")
        doThrow(failure).`when`(f.jdbc.statement).setString(2, "ROLE_USER")

        val thrown = assertThrows(UncategorizedSQLException::class.java) {
            f.query.update(8, Changes("Beta", null, null))
        }

        assertSame(failure, thrown.sqlException)
        verify(f.jdbc.statement).close()
        assertEquals(1, f.tx.rollbacks)
    }

    @ParameterizedTest
    @MethodSource("roleChanges")
    fun given__a_role_change__when__updating__then__revoke_all_sessions_only_on_a_demotion_inside_the_same_transaction(
        current: Role,
        requested: Role,
        revokes: Boolean,
    ) {
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = if (current == Role.ROLE_SHELTER) "A" else null
        val revoked = ArrayList<Long>()
        val transaction = TransactionTemplate(f.tx)
        transaction.timeout = 15
        val query = JdbcAdminUserUpdate(
            f.jdbc,
            transaction,
            Supplier { MODIFIED },
            UserSessionRevoker { userId ->
                assertTrue(f.tx.active)
                assertEquals("update", f.jdbc.events.last())
                revoked.add(userId)
            },
        )

        query.update(8, Changes(null, requested, null))

        assertEquals(if (revokes) listOf(8L) else listOf<Long>(), revoked)
        assertEquals(1, f.tx.commits)
    }

    @Test
    fun given__a_demotion_whose_revocation_fails__when__updating__then__roll_back_the_role_change_too() {
        val f = Fixture()
        f.jdbc.role = Role.ROLE_ADMIN
        val transaction = TransactionTemplate(f.tx)
        transaction.timeout = 15
        val failure = DataAccessResourceFailureException("synthetic revoke")
        val query = JdbcAdminUserUpdate(
            f.jdbc,
            transaction,
            Supplier { MODIFIED },
            UserSessionRevoker { userId -> throw failure },
        )

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) { query.update(8, Changes(null, Role.ROLE_USER, null)) },
        )

        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.tx.commits)
    }

    private class Fixture {
        val tx = RecordingTransactions()
        val jdbc = RecordingJdbc(tx)
        var samples = 0
        val query: JdbcAdminUserUpdate

        init {
            val transaction = TransactionTemplate(tx)
            transaction.timeout = 15
            query = JdbcAdminUserUpdate(
                jdbc,
                transaction,
                Supplier {
                    assertTrue(tx.active)
                    samples++
                    MODIFIED
                },
            )
        }
    }

    private class RecordingJdbc(private val tx: RecordingTransactions) : JdbcTemplate() {
        val rows: ResultSet = mock(ResultSet::class.java)
        val statement: PreparedStatement = mock(PreparedStatement::class.java)
        val events = ArrayList<String>()
        var present = true
        var duplicate = false
        var role = Role.ROLE_USER
        var care: String? = null
        var lockSql: String? = null
        var duplicateSql: String? = null
        var updateSql: String? = null
        var lockArguments: Array<out Any?>? = null
        var duplicateArguments: Array<out Any?>? = null
        var changed = 1
        var updateFailure: RuntimeException? = null

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            assertTrue(tx.active)
            events.add("lock")
            lockSql = normalized(sql)
            lockArguments = args
            try {
                `when`(rows.next()).thenReturn(present)
                `when`(rows.getString("nickname")).thenReturn("Alpha")
                `when`(rows.getString("role")).thenReturn(role.name)
                `when`(rows.getString("care_reg_no")).thenReturn(care)
                return rse.extractData(rows)
            } catch (e: SQLException) {
                throw AssertionError(e)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            assertTrue(tx.active)
            events.add("duplicate")
            duplicateSql = normalized(sql)
            duplicateArguments = args
            return java.lang.Boolean.valueOf(duplicate) as T
        }

        override fun update(psc: PreparedStatementCreator): Int {
            assertTrue(tx.active)
            events.add("update")
            val connection = mock(Connection::class.java)
            try {
                `when`(connection.prepareStatement(anyString())).thenAnswer { call ->
                    updateSql = normalized(call.getArgument(0))
                    statement
                }
                assertSame(statement, psc.createPreparedStatement(connection))
            } catch (e: SQLException) {
                throw UncategorizedSQLException("owned unit", updateSql, e)
            }
            if (updateFailure != null) {
                throw updateFailure!!
            }
            return changed
        }
    }

    private class RecordingTransactions : PlatformTransactionManager {
        var active = false
        var starts = 0
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertFalse(definition!!.isReadOnly)
            assertEquals(15, definition.timeout)
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.isolationLevel)
            starts++
            if (beginFailure != null) {
                throw beginFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
        }
    }

    companion object {
        private val MODIFIED: LocalDateTime = LocalDateTime.of(2030, 2, 3, 4, 5, 6, 123456000)

        @JvmStatic
        fun approvalRequests(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_USER, null, Changes("Duplicate", Role.ROLE_SHELTER, null)),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes("Duplicate", null, "B")),
            Arguments.of(Role.ROLE_SHELTER, null, Changes(null, null, "A")),
        )

        @JvmStatic
        fun noChanges(): Stream<Changes> = Stream.of(
            Changes(null, null, null),
            Changes("Alpha", Role.ROLE_USER, "A"),
            Changes(null, null, "  "),
            Changes("", null, null),
        )

        @JvmStatic
        fun validChanges(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_USER, null, Changes("123", null, null), "123", Role.ROLE_USER),
            Arguments.of(Role.ROLE_USER, null, Changes(null, Role.ROLE_ADMIN, null), "Alpha", Role.ROLE_ADMIN),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes(null, Role.ROLE_USER, "A"), "Alpha", Role.ROLE_USER),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes("Beta", Role.ROLE_SHELTER, "A"), "Beta", Role.ROLE_SHELTER),
        )

        @JvmStatic
        fun roleChanges(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_ADMIN, Role.ROLE_USER, true),
            Arguments.of(Role.ROLE_SHELTER, Role.ROLE_USER, true),
            Arguments.of(Role.ROLE_USER, Role.ROLE_ADMIN, false),
            Arguments.of(Role.ROLE_SHELTER, Role.ROLE_ADMIN, false),
        )
    }
}
