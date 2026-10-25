package com.pawbridge.backend.user.internal.animalcommand.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

class JdbcAnimalCommandActorsTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val member: ResultSet = mock(ResultSet::class.java)
    private val resource = ConnectionHolder(mock(Connection::class.java))
    private val actors = JdbcAnimalCommandActors(jdbc)

    @BeforeEach
    fun givenSharedWriterTransactionAndAnExistingMember() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
        `when`(jdbc.dataSource).thenReturn(source)
        `when`(member.getLong("user_id")).thenReturn(7L)
        `when`(jdbc.query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))).thenAnswer { call ->
            listOf(call.getArgument<RowMapper<*>>(1).mapRow(member, 0))
        }
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(false)
    }

    @AfterEach
    fun releaseOnlyTheTestTransactionBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @CsvSource(value = ["ROLE_ADMIN,<null>", "ROLE_SHELTER,care-7"], nullValues = ["<null>"])
    fun givenCurrentRoleAndNullableShelterIdentity__whenFindingTheVerifiedPrincipal__thenMapOnlyTheCurrentLockedUser(
        role: String,
        care: String?,
    ) {
        `when`(member.getString("role")).thenReturn(role)
        `when`(member.getString("care_reg_no")).thenReturn(care)

        assertEquals(Actor(7, role, care, true, false), actors.find(7))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))
        order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        verifyNoInteractions(source)
    }

    @Test
    fun givenPendingContactDeletion__whenFindingTheLockedUser__thenKeepRoleAndCareButReturnInactiveDeleted() {
        `when`(member.getString("role")).thenReturn("ROLE_SHELTER")
        `when`(member.getString("care_reg_no")).thenReturn("care-7")
        `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenReturn(true)

        assertEquals(Actor(7, "ROLE_SHELTER", "care-7", false, true), actors.find(7))

        val order = inOrder(jdbc)
        order.verify(jdbc).query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))
        order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
    }

    @Test
    fun givenNoCurrentUser__whenFindingTheVerifiedPrincipal__thenReturnInactiveDeletedWithoutInventingARoleOrReadingPending() {
        `when`(jdbc.query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))).thenReturn(listOf())

        assertEquals(Actor(7, null, null, false, true), actors.find(7))

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    @Test
    fun givenNoWritableTransactionOrOnlyAnotherDataSourceBinding__whenFinding__thenRejectBeforeAnySql() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { actors.find(7) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        assertThrows(IllegalStateException::class.java) { actors.find(7) }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.unbindResource(source)
        val other = mock(DataSource::class.java)
        TransactionSynchronizationManager.bindResource(other, resource)
        try {
            assertThrows(IllegalStateException::class.java) { actors.find(7) }
        } finally {
            TransactionSynchronizationManager.unbindResource(other)
        }
        verify(jdbc, never()).query(anyString(), any<RowMapper<Any>>(), anyLong())
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    @Test
    fun givenNonpositiveVerifiedIdentity__whenFinding__thenRejectBeforeAnySql() {
        assertAll(
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.find(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.find(-1) } },
        )
        verify(jdbc, never()).query(anyString(), any<RowMapper<Any>>(), anyLong())
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["member", "pending"])
    fun givenSqlFailure__whenLockingOrObservingPendingDeletion__thenPropagateTheSameFailureAndDoNotContinue(phase: String) {
        val failure = DataAccessResourceFailureException("Synthetic lookup unavailable")
        if (phase == "member") {
            `when`(jdbc.query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))).thenThrow(failure)
        } else {
            `when`(jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { actors.find(7) })

        if (phase == "member") {
            verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), anyLong())
        } else {
            val order = inOrder(jdbc)
            order.verify(jdbc).query(eq(MEMBER), any<RowMapper<Any>>(), eq(7L))
            order.verify(jdbc).queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    companion object {
        private const val MEMBER = "SELECT user_id,role,care_reg_no FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
