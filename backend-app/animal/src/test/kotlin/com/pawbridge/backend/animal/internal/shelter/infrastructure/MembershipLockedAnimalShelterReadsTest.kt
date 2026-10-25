package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class MembershipLockedAnimalShelterReadsTest {
    private val source: DataSource = mockk<DataSource>()
    private val other: DataSource = mockk<DataSource>()
    private val jdbc: JdbcTemplate = mockk<JdbcTemplate>()
    private val delegate: AnimalShelterReads = mockk<AnimalShelterReads>()
    private val row: ResultSet = mockk<ResultSet>()
    private val binding = ConnectionHolder(mockk<Connection>())
    private val snapshot = AnimalShelterReads.Shelter(7L, "care", "full detail name")
    private val reads = MembershipLockedAnimalShelterReads(delegate, jdbc)

    @BeforeEach
    fun bindCallerWriterScope() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, binding)
    }

    @AfterEach
    fun releaseOnlyTestBindings() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.unbindResourceIfPossible(other)
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `전체 상세가 있을 때__멤버십을 확인하면__위임 뒤에 잠그고 위임이 준 스냅샷을 그대로 돌려준다`() {
        // given
        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } returns snapshot
        every { row.getObject("id", Long::class.javaObjectType) } returns 7L
        every { row.getString("care_reg_no") } returns "care"
        every { row.getString("name") } returns "current name"
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(arg<RowMapper<*>>(1).mapRow(row, 0)!!)
        }

        // when
        val found = reads.findByCareRegNo("care")

        // then
        assertSame(snapshot, found)
        verifyOrder {
            delegate.findByCareRegNo("care")
            jdbc.query(SQL, any<RowMapper<Any>>(), "care")
        }
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        verify(exactly = 0) { source.connection }
        verify(exactly = 0) { other.connection }
    }

    @Test
    fun `위임이 비어 있을 때__확인하면__쓰기 쿼리 없이 null을 유지한다`() {
        // given
        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } returns null

        // when
        val found = reads.findByCareRegNo("care")

        // then
        assertNull(found)
        verify(exactly = 1) { delegate.findByCareRegNo("care") }
        noSql()
    }

    @Test
    fun `전체 상세 조회가 실패할 때__확인하면__잠그지 않고 그 실패를 보존한다`() {
        // given
        val failure = AnimalShelterReadUnavailableException(IllegalStateException("Synthetic public info failure"))

        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } throws failure

        // when
        val thrown = assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { delegate.findByCareRegNo("care") }
        noSql()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "id", "registration", "delegateRegistration", "nullId"])
    fun `행이 바뀌었거나 없을 때__잠그면__고정된 이용 불가 메시지만 돌려준다`(mode: String) {
        // given
        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } returns snapshot
        every { row.getObject("id", Long::class.javaObjectType) } returns 7L
        every { row.getString("care_reg_no") } returns "care"
        every { row.getString("name") } returns "current name"
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(arg<RowMapper<*>>(1).mapRow(row, 0)!!)
        }

        if (mode == "missing") {
            every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns listOf()
        }
        if (mode == "id") {
            every { row.getObject("id", Long::class.javaObjectType) } returns 8L
        }
        if (mode == "registration") {
            every { row.getString("care_reg_no") } returns "other"
        }
        if (mode == "delegateRegistration") {
            every { delegate.findByCareRegNo(any()) } returns AnimalShelterReads.Shelter(7L, "other", "x")
        }
        if (mode == "nullId") {
            every { delegate.findByCareRegNo(any()) } returns AnimalShelterReads.Shelter(null, "care", "x")
        }

        // when
        val thrown = assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }

        // then
        assertEquals("Animal shelter information could not be read", thrown.message)
        verify(exactly = 1) { delegate.findByCareRegNo("care") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "missingSource"])
    fun `쓰기 범위가 없을 때__확인하면__위임이나 SQL 전에 거부한다`(mode: String) {
        // given
        every { jdbc.dataSource } returns (if (mode == "missingSource") null else source)

        invalidate(mode)

        // when
        val thrown = assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }

        // then
        assertEquals("Animal shelter information could not be read", thrown.message)
        confirmVerified(delegate)
        noSql()
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "replacement"])
    fun `위임이 쓰기 범위를 바꿀 때__돌려주면__잠그기 전에 거부한다`(mode: String) {
        // given
        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } answers {
            invalidate(mode)
            snapshot
        }

        // when
        val thrown = assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }

        // then
        assertEquals("Animal shelter information could not be read", thrown.message)
        verify(exactly = 1) { delegate.findByCareRegNo("care") }
        noSql()
    }

    @Test
    fun `잠금 쿼리가 실패할 때__확인하면__이용 불가 분류를 유지한다`() {
        // given
        val failure = DataAccessResourceFailureException("Synthetic shelter lock failure")

        every { jdbc.dataSource } returns source
        every { delegate.findByCareRegNo(any()) } returns snapshot
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } throws failure

        // when
        val thrown = assertThrows(AnimalShelterReadUnavailableException::class.java) { reads.findByCareRegNo("care") }

        // then
        assertSame(failure, thrown.cause)
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
        verify(exactly = 1) { jdbc.query(SQL, any<RowMapper<Any>>(), "care") }
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
                TransactionSynchronizationManager.bindResource(source, ConnectionHolder(mockk<Connection>()))
            }
            else -> Unit
        }
    }

    private fun noSql() {
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    companion object {
        private const val SQL = "SELECT id,care_reg_no,name FROM pawbridge_animal.shelters WHERE care_reg_no=? FOR KEY SHARE"
    }
}
