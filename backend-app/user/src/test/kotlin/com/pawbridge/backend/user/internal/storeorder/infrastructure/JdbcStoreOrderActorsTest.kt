package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors.Actor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
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

class JdbcStoreOrderActorsTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val member: ResultSet = mock(ResultSet::class.java)
    private val resource = ConnectionHolder(mock(Connection::class.java))
    private val actors = JdbcStoreOrderActors(jdbc)

    @BeforeEach
    fun givenTheSharedWriterBindingAndAnExistingMember() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
        `when`(jdbc.dataSource).thenReturn(source)
        `when`(member.getLong("user_id")).thenReturn(7L)
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenAnswer { call ->
            listOf(call.getArgument<RowMapper<*>>(1).mapRow(member, 0))
        }
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(false)
    }

    @AfterEach
    fun releaseOnlyTheTestBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @ValueSource(strings = ["write", "readOnly", "readWrite"])
    fun givenTheCurrentMemberAndCallerTransaction__whenReadingState__thenLockOnlyForWriteAndObserveReadStateInOneStatement(
        mode: String,
    ) {
        val write = mode == "write"
        val readOnly = mode == "readOnly"
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly)

        assertEquals(Actor(7, true, false), current(write))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(if (write) LOCK_MEMBER else READ_MEMBER), any<RowMapper<Any>>(), eq(7L))
        if (write) {
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        } else {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        }
        verify(jdbc, never()).query(eq(if (write) READ_MEMBER else LOCK_MEMBER), any<RowMapper<Any>>(), anyLong())
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        verifyNoInteractions(source)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun givenNoCurrentUser__whenReadingForWriteOrRead__thenReturnInactiveDeletedWithoutReadingPending(write: Boolean) {
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenReturn(listOf())

        assertEquals(Actor(7, false, true), current(write))

        verify(jdbc).query(eq(if (write) LOCK_MEMBER else READ_MEMBER), any<RowMapper<Any>>(), eq(7L))
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun givenPendingContactDeletion__whenReadingForWriteOrRead__thenReturnInactiveDeletedAfterTheMemberQuery(write: Boolean) {
        `when`(member.getBoolean("pending")).thenReturn(true)
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(true)

        assertEquals(Actor(7, false, true), current(write))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(if (write) LOCK_MEMBER else READ_MEMBER), any<RowMapper<Any>>(), eq(7L))
        if (write) {
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        } else {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun givenNoActualTransactionOrNoMatchingDataSourceBinding__whenReadingEitherMode__thenRejectBeforeSql(write: Boolean) {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.unbindResource(source)
        val other = mock(DataSource::class.java)
        TransactionSynchronizationManager.bindResource(other, resource)
        try {
            assertThrows(IllegalStateException::class.java) { current(write) }
        } finally {
            TransactionSynchronizationManager.unbindResource(other)
        }
        TransactionSynchronizationManager.bindResource(source, resource)
        `when`(jdbc.dataSource).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { current(write) }
        verifyNoMemberSql()
    }

    @Test
    fun givenTheBoundReadOnlyTransaction__whenRequestingWriteState__thenRejectBeforeTakingAUserRowLock() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

        assertThrows(IllegalStateException::class.java) { actors.currentForWrite(7) }

        verifyNoMemberSql()
    }

    @Test
    fun givenNonpositiveVerifiedIdentity__whenReadingEitherMode__thenRejectBeforeSql() {
        assertAll(
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForWrite(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForWrite(-1) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForRead(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForRead(-1) } },
        )
        verifyNoMemberSql()
    }

    @ParameterizedTest
    @CsvSource("true,member", "false,member", "true,pending")
    fun givenSqlFailure__whenReadingTheMemberOrPendingState__thenPropagateTheSameFailureWithoutFurtherWork(
        write: Boolean,
        phase: String,
    ) {
        val failure = DataAccessResourceFailureException("Synthetic current member unavailable")
        if (phase == "member") {
            `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), eq(7L))).thenThrow(failure)
        } else {
            `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { current(write) })

        if (phase == "member") {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        } else {
            val order = inOrder(jdbc)
            order.verify(jdbc).query(eq(LOCK_MEMBER), any<RowMapper<Any>>(), eq(7L))
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    private fun current(write: Boolean): Actor? {
        return if (write) actors.currentForWrite(7) else actors.currentForRead(7)
    }

    private fun verifyNoMemberSql() {
        verify(jdbc, never()).query(anyString(), any<RowMapper<Any>>(), anyLong())
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    companion object {
        private const val MEMBER = "SELECT user_id FROM pawbridge_user.users WHERE user_id=?"
        private const val LOCK_MEMBER = MEMBER + " FOR UPDATE"
        private val READ_MEMBER = """
            SELECT u.user_id, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
