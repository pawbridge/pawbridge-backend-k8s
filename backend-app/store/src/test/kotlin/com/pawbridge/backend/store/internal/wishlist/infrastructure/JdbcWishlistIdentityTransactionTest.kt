package com.pawbridge.backend.store.internal.wishlist.infrastructure

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

class JdbcWishlistIdentityTransactionTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val manager: PlatformTransactionManager = mock(PlatformTransactionManager::class.java)
    private val clock: Clock = mock(Clock::class.java)
    private val status = SimpleTransactionStatus()
    private val commands = JdbcWishlistCommands(jdbc, TransactionTemplate(manager), clock)

    @BeforeEach
    fun prepare() {
        `when`(manager.getTransaction(any())).thenReturn(status)
        `when`(jdbc.update(DELETE, mapOf("member" to 7L, "id" to 33L))).thenReturn(1)
    }

    @Test
    fun given__identity_transaction_begin_failure__when__removing__then__propagate_without_sql() {
        val failure = IllegalStateException("synthetic begin")
        doThrow(failure).`when`(manager).getTransaction(any())

        assertSame(failure, assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) })

        verifyNoInteractions(jdbc, clock)
        verify(manager, never()).commit(any())
        verify(manager, never()).rollback(any())
    }

    @Test
    fun given__identity_delete_failure__when__removing__then__rollback_and_propagate_without_retry() {
        val failure = DataAccessResourceFailureException("synthetic delete")
        doThrow(failure).`when`(jdbc).update(DELETE, mapOf("member" to 7L, "id" to 33L))

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { commands.removeById(7, 33) })

        verify(manager).rollback(status)
        verify(manager, never()).commit(any())
        verify(jdbc).update(DELETE, mapOf("member" to 7L, "id" to 33L))
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
    }

    @Test
    fun given__identity_commit_failure__when__removing__then__propagate_instead_of_claiming_success() {
        val failure = IllegalStateException("synthetic commit")
        doThrow(failure).`when`(manager).commit(status)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) })

        verify(manager).commit(status)
        verify(jdbc).update(DELETE, mapOf("member" to 7L, "id" to 33L))
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
    }

    @Test
    fun given__identity_rollback_failure__when__delete_fails__then__propagate_manager_failure() {
        val deleteFailure = DataAccessResourceFailureException("synthetic delete")
        val rollbackFailure = IllegalStateException("synthetic rollback")
        doThrow(deleteFailure).`when`(jdbc).update(DELETE, mapOf("member" to 7L, "id" to 33L))
        doThrow(rollbackFailure).`when`(manager).rollback(status)

        assertSame(rollbackFailure, assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) })

        verify(manager).rollback(status)
        verify(manager, never()).commit(any())
        verify(jdbc).update(DELETE, mapOf("member" to 7L, "id" to 33L))
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
    }

    companion object {
        private const val DELETE = "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member"
    }
}
