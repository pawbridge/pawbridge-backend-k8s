package com.pawbridge.backend.user.internal.shelter.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

class JdbcAnimalShelterMembersTest {
    private val source: DataSource = mockk<DataSource>()
    private val other: DataSource = mockk<DataSource>()
    private val jdbc: JdbcTemplate = mockk<JdbcTemplate>()
    private val binding = ConnectionHolder(mockk<Connection>())
    private val members = JdbcAnimalShelterMembers(jdbc)

    @BeforeEach
    fun bindCaller() {
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

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `보호소 회원 연관이 남아 있거나 없을 때__관찰하면__역할이나 대기 조건 없이 원본 등록번호를 바인딩한다`(
        exists: Boolean,
    ) {
        // given
        every { jdbc.dataSource } returns source
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns exists

        // when
        val observed = members.exists(" care ")

        // then
        assertEquals(exists, observed)
        verify(exactly = 1) { jdbc.queryForObject(SQL, Boolean::class.javaObjectType, " care ") }
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        // The strict mocks fail on any call; hashCode is only used by the transaction resource map.
        verify(exactly = 0) { source.connection }
        verify(exactly = 0) { other.connection }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "readOnly", "other", "missingSource"])
    fun `일치하는 쓰기 범위가 없을 때__관찰하면__사용자 SQL 전에 거부한다`(mode: String) {
        // given
        every { jdbc.dataSource } returns if (mode == "missingSource") null else source

        when (mode) {
            "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false)
            "readOnly" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
            "other" -> {
                TransactionSynchronizationManager.unbindResource(source)
                TransactionSynchronizationManager.bindResource(other, binding)
            }
        }

        // when
        assertThrows(IllegalStateException::class.java) { members.exists("care") }

        // then
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "sql"])
    fun `관찰 결과를 얻을 수 없을 때__읽으면__삭제를 허용하지 않고 실패한다`(mode: String) {
        // given
        val failure = DataAccessResourceFailureException("Synthetic member observation failure")

        every { jdbc.dataSource } returns source
        if (mode == "sql") {
            every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } throws failure
        } else {
            every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns null
        }

        // when
        if (mode == "sql") {
            assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { members.exists("care") })
        } else {
            assertThrows(NullPointerException::class.java) { members.exists("care") }
        }

        // then
        verify(exactly = 1) { jdbc.queryForObject(SQL, Boolean::class.javaObjectType, "care") }
        assertSame(binding, TransactionSynchronizationManager.getResource(source))
    }

    companion object {
        private const val SQL = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE care_reg_no=?)"
    }
}
