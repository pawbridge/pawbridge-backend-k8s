package com.pawbridge.backend.user.internal.session.infrastructure

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/**
 * The one refresh branch a real database cannot produce: the session exists (it cascades with its user) but the user
 * row is gone when the claims are read. Everything else of the refresh runs against PostgreSQL in the boot contract
 * tests (`AuthSessionContractTest`).
 */
class AuthSessionVanishedUserTest {
    @Test
    fun `회전 뒤 사용자가 사라졌을 때__갱신하면__유효하지 않음으로 거부하고 트랜잭션을 롤백한다`() {
        // given
        val jdbc = mockk<JdbcOperations>()
        every { jdbc.query(any<String>(), any<RowMapper<JwtSessionTokens.UserClaims>>(), *anyVararg()) } returns emptyList()
        val manager = mockk<PlatformTransactionManager>(relaxed = true)
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        val store = mockk<JdbcAuthSessionStore>()
        every { store.rotate(any(), any()) } returns Optional.of(Rotation(31, 7, 10))
        val refreshTokens = RefreshTokens()
        val sessions = JdbcAuthSessions(jdbc, TransactionTemplate(manager), mockk<JwtSessionTokens>(), store, refreshTokens)

        // when
        val failure = assertThrows(AuthSessionRejectedException::class.java) { sessions.refresh(refreshTokens.generate()) }

        // then
        assertEquals(Reason.INVALID, failure.reason())
        verify(exactly = 1) { manager.rollback(any()) }
        verify(exactly = 0) { manager.commit(any()) }
    }
}
