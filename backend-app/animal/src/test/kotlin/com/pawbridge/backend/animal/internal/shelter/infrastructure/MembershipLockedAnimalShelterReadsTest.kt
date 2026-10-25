package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
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

class MembershipLockedAnimalShelterReadsTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val other: DataSource = mock(DataSource::class.java)
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val delegate: AnimalShelterReads = mock(AnimalShelterReads::class.java)
    private val row: ResultSet = mock(ResultSet::class.java)
    private val binding = ConnectionHolder(mock(Connection::class.java))
    private val snapshot = AnimalShelterReads.Shelter(7L, "care", "full detail name")
    private val reads = MembershipLockedAnimalShelterReads(delegate, jdbc)

    @BeforeEach
    fun bindCallerAndMatchingRow() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, binding)
        `when`(jdbc.dataSource).thenReturn(source)
        `when`(delegate.findByCareRegNo("care")).thenReturn(snapshot)
        `when`(row.getObject("id", Long::class.javaObjectType)).thenReturn(7L)
        `when`(row.getString("care_reg_no")).thenReturn("care")
        `when`(row.getString("name")).thenReturn("current name")
        `when`(jdbc.query(eq(SQL), any<RowMapper<Any>>(), eq("care"))).thenAnswer { call ->
            val mapper: RowMapper<*> = call.getArgument(1)
            listOf(mapper.mapRow(row, 0))
        }
    }

    @AfterEach
    fun releaseOnlyTestBindings() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.unbindResourceIfPossible(other)
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun given__full_detail__when__confirming_membership__then__lock_after_delegate_and_return_its_unchanged_snapshot() {
        assertSame(snapshot, reads.findByCareRegNo("care"))

        val order = inOrder(delegate, jdbc)
        order.verify(delegate).findByCareRegNo("care")
        order.verify(jdbc).query(eq(SQL), any<RowMapper<Any>>(), eq("care"))
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        verifyNoInteractions(source, other)
    }

    @Test
    fun given__empty_delegate__when__confirming__then__keep_null_without_writer_query() {
        `when`(delegate.findByCareRegNo("care")).thenReturn(null)

        assertNull(reads.findByCareRegNo("care"))
        noSql()
    }

    @Test
    fun given__full_detail_failure__when__confirming__then__preserve_failure_without_locking() {
        val failure = AnimalShelterReadUnavailableException(IllegalStateException("Synthetic public info failure"))
        `when`(delegate.findByCareRegNo("care")).thenThrow(failure)

        assertSame(failure, assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") })
        noSql()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "id", "registration", "delegateRegistration", "nullId"])
    fun given__changed_or_missing_row__when__locking__then__return_only_fixed_unavailable(mode: String) {
        if (mode == "missing") {
            `when`(jdbc.query(eq(SQL), any<RowMapper<Any>>(), eq("care"))).thenReturn(listOf())
        }
        if (mode == "id") {
            `when`(row.getObject("id", Long::class.javaObjectType)).thenReturn(8L)
        }
        if (mode == "registration") {
            `when`(row.getString("care_reg_no")).thenReturn("other")
        }
        if (mode == "delegateRegistration") {
            `when`(delegate.findByCareRegNo("care")).thenReturn(AnimalShelterReads.Shelter(7L, "other", "x"))
        }
        if (mode == "nullId") {
            `when`(delegate.findByCareRegNo("care")).thenReturn(AnimalShelterReads.Shelter(null, "care", "x"))
        }

        assertEquals(
            "Animal shelter information could not be read",
            assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }.message,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "missingSource"])
    fun given__no_writer_scope__when__confirming__then__reject_before_delegate_or_sql(mode: String) {
        invalidate(mode)

        assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }
        verifyNoInteractions(delegate)
        noSql()
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "replacement"])
    fun given__delegate_changes_writer_scope__when__returning__then__reject_before_locking(mode: String) {
        `when`(delegate.findByCareRegNo("care")).thenAnswer {
            invalidate(mode)
            snapshot
        }

        assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }
        noSql()
    }

    @Test
    fun given__lock_query_failure__when__confirming__then__keep_unavailable_classification() {
        val failure = DataAccessResourceFailureException("Synthetic shelter lock failure")
        `when`(jdbc.query(eq(SQL), any<RowMapper<Any>>(), eq("care"))).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }.cause,
        )
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
    }

    private fun invalidate(mode: String) {
        when (mode) {
            "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false)
            "readOnly" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
            "other" -> {
                TransactionSynchronizationManager.unbindResource(source)
                TransactionSynchronizationManager.bindResource(other, binding)
            }
            "replacement" -> {
                TransactionSynchronizationManager.unbindResource(source)
                TransactionSynchronizationManager.bindResource(source, ConnectionHolder(mock(Connection::class.java)))
            }
            else -> `when`(jdbc.dataSource).thenReturn(null)
        }
    }

    private fun noSql() {
        verify(jdbc, never()).query(anyString(), any<RowMapper<Any>>(), any())
    }

    companion object {
        private const val SQL = "SELECT id,care_reg_no,name FROM pawbridge_animal.shelters WHERE care_reg_no=? FOR KEY SHARE"
    }
}
