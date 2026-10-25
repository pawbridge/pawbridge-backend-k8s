package com.pawbridge.backend.store.internal.wishlist.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

class JdbcWishlistIdentityTransactionTest {
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val manager: PlatformTransactionManager = mockk<PlatformTransactionManager>()
    private val clock: Clock = mockk<Clock>()
    private val status = SimpleTransactionStatus()
    private val commands = JdbcWishlistCommands(jdbc, TransactionTemplate(manager), clock)

    @Test
    fun `ID 삭제 트랜잭션 시작이 실패할 때__삭제하면__SQL 없이 그대로 전파한다`() {
        // given
        val failure = IllegalStateException("synthetic begin")

        every { manager.getTransaction(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) }

        // then
        assertSame(failure, thrown)
        confirmVerified(jdbc, clock)
        verify(exactly = 0) { manager.commit(any()) }
        verify(exactly = 0) { manager.rollback(any()) }
    }

    @Test
    fun `ID 삭제 쿼리가 실패할 때__삭제하면__롤백하고 재시도 없이 그대로 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic delete")

        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { commands.removeById(7, 33) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
        verify(exactly = 1) { jdbc.update(DELETE, mapOf("member" to 7L, "id" to 33L)) }
        confirmVerified(jdbc)
        confirmVerified(clock)
    }

    @Test
    fun `ID 삭제 커밋이 실패할 때__삭제하면__성공으로 주장하지 않고 그대로 전파한다`() {
        // given
        val failure = IllegalStateException("synthetic commit")

        every { manager.getTransaction(any()) } returns status
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } returns 1
        every { manager.commit(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { manager.commit(status) }
        verify(exactly = 1) { jdbc.update(DELETE, mapOf("member" to 7L, "id" to 33L)) }
        confirmVerified(jdbc)
        confirmVerified(clock)
    }

    @Test
    fun `ID 삭제가 실패하고 롤백도 실패할 때__삭제하면__트랜잭션 관리자의 실패를 전파한다`() {
        // given
        val deleteFailure = DataAccessResourceFailureException("synthetic delete")
        val rollbackFailure = IllegalStateException("synthetic rollback")

        every { manager.getTransaction(any()) } returns status
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } throws deleteFailure
        every { manager.rollback(any()) } throws rollbackFailure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { commands.removeById(7, 33) }

        // then
        assertSame(rollbackFailure, thrown)
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
        verify(exactly = 1) { jdbc.update(DELETE, mapOf("member" to 7L, "id" to 33L)) }
        confirmVerified(jdbc)
        confirmVerified(clock)
    }

    companion object {
        private const val DELETE = "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member"
    }
}
