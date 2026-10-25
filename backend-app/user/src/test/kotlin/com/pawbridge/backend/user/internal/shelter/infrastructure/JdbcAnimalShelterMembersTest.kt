package com.pawbridge.backend.user.internal.shelter.infrastructure

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

class JdbcAnimalShelterMembersTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val other: DataSource = mock(DataSource::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val binding = ConnectionHolder(mock(Connection::class.java))
    private val members = JdbcAnimalShelterMembers(jdbc)

    @BeforeEach
    fun bindCaller() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, binding)
        `when`(jdbc.dataSource).thenReturn(source)
    }

    @AfterEach
    fun releaseOnlyTestBindings() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.unbindResourceIfPossible(other)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__any_still_present_association__when__observing__then__bind_raw_registration_without_role_or_pending_filter(
        exists: Boolean,
    ) {
        `when`(jdbc.queryForObject(SQL, Boolean::class.javaObjectType, " care ")).thenReturn(exists)

        assertEquals(exists, members.exists(" care "))

        verify(jdbc).queryForObject(SQL, Boolean::class.javaObjectType, " care ")
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        verifyNoInteractions(source, other)
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "missingSource"])
    fun given__no_matching_writer_scope__when__observing__then__reject_before_user_sql(mode: String) {
        when (mode) {
            "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false)
            "readOnly" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
            "other" -> {
                TransactionSynchronizationManager.unbindResource(source)
                TransactionSynchronizationManager.bindResource(other, binding)
            }
            else -> `when`(jdbc.dataSource).thenReturn(null)
        }

        assertThrows(IllegalStateException::class.java) { members.exists("care") }

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean::class.javaObjectType), any())
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "sql"])
    fun given__unavailable_observation__when__reading__then__fail_instead_of_allowing_deletion(mode: String) {
        val failure = DataAccessResourceFailureException("Synthetic member observation failure")
        if (mode == "sql") {
            `when`(jdbc.queryForObject(SQL, Boolean::class.javaObjectType, "care")).thenThrow(failure)
        } else {
            `when`(jdbc.queryForObject(SQL, Boolean::class.javaObjectType, "care")).thenReturn(null)
        }

        if (mode == "sql") {
            assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { members.exists("care") })
        } else {
            assertThrows(NullPointerException::class.java) { members.exists("care") }
        }
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
    }

    companion object {
        private const val SQL = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE care_reg_no=?)"
    }
}
