package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderAdministrators.Actor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class JdbcStoreOrderAdministratorsTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val member: ResultSet = mock(ResultSet::class.java)
    private val resource = ConnectionHolder(mock(Connection::class.java))
    private val administrators = JdbcStoreOrderAdministrators(jdbc)

    @BeforeEach
    fun given__a_shared_writer_binding__when__preparing__then__observe_current_user_rows() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
        `when`(jdbc.dataSource).thenReturn(source)
        `when`(member.getLong("user_id")).thenReturn(7L)
        `when`(member.getString("role")).thenReturn("ROLE_ADMIN")
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenAnswer { call ->
            listOf(call.getArgument<RowMapper<*>>(1).mapRow(member, 0))
        }
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(false)
    }

    @AfterEach
    fun release__the_test_binding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @CsvSource("true,ROLE_ADMIN", "false,ROLE_ADMIN", "true,ROLE_USER", "false,admin", "false,ROLE_SHELTER")
    fun given__current_db_roles__when__observing_inside_the_matching_transaction__then__preserve_roles_and_lock_only_for_write(
        write: Boolean,
        role: String,
    ) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        `when`(member.getString("role")).thenReturn(role)

        assertEquals(Actor(7, true, false, role), current(write))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(if (write) LOCK else READ), any<RowMapper<Any>>(), eq(7L))
        if (write) {
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        } else {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        }
        verify(jdbc, never()).query(eq(if (write) READ else LOCK), any<RowMapper<Any>>(), anyLong())
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        assertEquals(!write, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        verifyNoInteractions(source)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__a_missing_user__when__reading_current_authority__then__return_unavailable_without_pending_query(write: Boolean) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenReturn(listOf())

        assertEquals(Actor(7, false, true, null), current(write))

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__pending_contact_deletion__when__reading_authority__then__keep_db_role_but_disable_the_actor(write: Boolean) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        `when`(member.getBoolean("pending")).thenReturn(true)
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(true)

        assertEquals(Actor(7, false, true, "ROLE_ADMIN"), current(write))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(if (write) LOCK else READ), any<RowMapper<Any>>(), eq(7L))
        if (write) {
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__missing_or_wrong_transaction_state__when__reading_authority__then__reject_before_any_user_sql(write: Boolean) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.unbindResource(source)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.bindResource(source, resource)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(write)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        `when`(jdbc.dataSource).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { current(write) }
        noSql()
    }

    @Test
    fun given__nonpositive_verified_ids__when__requesting_current_authority__then__reject_before_sql() {
        for (id in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { administrators.currentForRead(id) }
            assertThrows(IllegalArgumentException::class.java) { administrators.currentForWrite(id) }
        }
        noSql()
    }

    @ParameterizedTest
    @CsvSource("true,member", "false,member", "true,pending")
    fun given__sql_failure__when__observing_authority__then__propagate_the_same_failure_and_preserve_transaction_binding(
        write: Boolean,
        phase: String,
    ) {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        val failure = DataAccessResourceFailureException("Synthetic authority unavailable")
        if (phase == "member") {
            `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenThrow(failure)
        } else {
            `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { current(write) })

        if (phase == "member") {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    @Test
    fun given__an_unavailable_pending_observation__when__reading_write_authority__then__never_invent_an_active_administrator() {
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(null)

        assertThrows(NullPointerException::class.java) { administrators.currentForWrite(7) }
    }

    private fun current(write: Boolean): Actor? {
        return if (write) administrators.currentForWrite(7) else administrators.currentForRead(7)
    }

    private fun noSql() {
        verify(jdbc, never()).query(anyString(), any<RowMapper<Any>>(), anyLong())
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    companion object {
        private const val LOCK = "SELECT user_id,role FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private val READ = """
            SELECT u.user_id, u.role, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
