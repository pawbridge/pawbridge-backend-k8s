package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import javax.sql.DataSource

class ModuleAnimalShelterReadsTest {
    private val source: ShelterReads = mock(ShelterReads::class.java)
    private val manager: PlatformTransactionManager = mock(PlatformTransactionManager::class.java)
    private val reads = ModuleAnimalShelterReads(source, manager)

    init {
        `when`(manager.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }

    @Test
    fun givenRegistrationLookup__whenDelegating__thenReturnOnlySharedIdRegistrationAndNameWithRawInput() {
        `when`(source.findByCareRegNo(" 등록 번호 ")).thenReturn(
            ShelterReads.Details(
                9L, "등록 번호", "보호소", null, null,
                null, null, null, null, null, null, null, null, null,
            ),
        )

        assertThat(reads.findByCareRegNo(" 등록 번호 ")).isEqualTo(AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"))
        verify(source).findByCareRegNo(" 등록 번호 ")
        verifyNoMoreInteractions(source)
    }

    @Test
    fun givenModuleBoundary__whenCreatingAnimalReadScope__thenJoinOrStartReadOnlyDefaultIsolationAndTimeoutWithoutASecondConnection() {
        reads.findByCareRegNo("r")

        val definition = ArgumentCaptor.forClass(TransactionDefinition::class.java)
        verify(manager).getTransaction(definition.capture())
        assertThat(definition.value.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
        assertThat(definition.value.isReadOnly).isTrue()
        assertThat(definition.value.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definition.value.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(manager).commit(any())
    }

    @Test
    fun givenCurrentSourceMissingRegistrationFailure__whenDelegating__thenPreserveUnavailableInsteadOfInventing404OrEmpty() {
        val failure = IllegalStateException("Shelter not found: r")
        `when`(source.findByCareRegNo("r")).thenThrow(failure)

        assertThatThrownBy { reads.findByCareRegNo("r") }
            .isInstanceOf(AnimalShelterReadUnavailableException::class.java)
            .hasCause(failure)
        verify(manager).rollback(any())
    }

    @Test
    fun givenEmptySourceBody__whenDelegating__thenKeepNullForExistingCallerUnavailableChecks() {
        assertThat(reads.findByCareRegNo("r")).isNull()
        verify(source).findByCareRegNo("r")
    }

    @Test
    fun givenApprovalWriterTransactionOnTheSharedPool__whenLookingUp__thenJoinItWithoutBorrowingASecondConnection() {
        val pool = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        `when`(pool.connection).thenReturn(connection)
        `when`(connection.autoCommit).thenReturn(true)
        val readerManager = JdbcTransactionManager(pool)
        readerManager.isEnforceReadOnly = true
        `when`(source.findByCareRegNo("r")).thenReturn(
            ShelterReads.Details(
                9L, "r", "보호소", null, null,
                null, null, null, null, null, null, null, null, null,
            ),
        )
        val shared = ModuleAnimalShelterReads(source, readerManager)

        val found = TransactionTemplate(JdbcTransactionManager(pool)).execute { status -> shared.findByCareRegNo("r") }

        assertThat(found).isEqualTo(AnimalShelterReads.Shelter(9L, "r", "보호소"))
        verify(pool, times(1)).connection
        verify(connection, times(1)).commit()
    }
}
