package com.pawbridge.backend.user.internal.passwordreset.application

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.application.InvalidCodeException
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import io.mockk.Called
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.OptionalLong

class PasswordResetTest {
    private val accounts = mockk<PasswordResetAccounts>()
    private val verifications = mockk<EmailVerifications>()
    private val hashes = mockk<PasswordHashes>()
    private val reset = PasswordReset(accounts, verifications, hashes)

    @Test
    fun `로컬 계정일 때__요청하면__코드를 메일로 보낸다`() {
        // given
        every { accounts.findLocalUserId(any()) } returns OptionalLong.of(7)
        justRun { verifications.sendPasswordResetCode(any()) }

        // when
        reset.request("a@b.c")

        // then
        verify(exactly = 1) { verifications.sendPasswordResetCode("a@b.c") }
    }

    @Test
    fun `알 수 없는 계정이거나 메일 발송이 실패했을 때__요청하면__똑같이 조용히 성공한다`() {
        // given
        every { accounts.findLocalUserId("none@b.c") } returns OptionalLong.empty()
        every { accounts.findLocalUserId("a@b.c") } returns OptionalLong.of(7)
        every { verifications.sendPasswordResetCode(any()) } throws IllegalStateException("smtp")

        // when
        reset.request("none@b.c")
        reset.request("a@b.c")

        // then
        verify(exactly = 0) { verifications.sendPasswordResetCode("none@b.c") }
        verify(exactly = 1) { verifications.sendPasswordResetCode("a@b.c") }
    }

    @Test
    fun `유효한 코드일 때__재설정하면__조회 검증 인코딩 갱신 정리를 원래 순서로 수행한다`() {
        // given
        every { accounts.findLocalUserId(any()) } returns OptionalLong.of(7)
        every { verifications.verifyPasswordResetCode(any(), any()) } returns true
        every { hashes.encode(any()) } returns "ENC"
        justRun { accounts.updatePassword(any(), any()) }
        justRun { verifications.clearPasswordResetVerification(any()) }

        // when
        reset.reset("a@b.c", "123456", "NewPass1!")

        // then
        verifySequence {
            accounts.findLocalUserId("a@b.c")
            verifications.verifyPasswordResetCode("a@b.c", "123456")
            hashes.encode("NewPass1!")
            accounts.updatePassword(7, "ENC")
            verifications.clearPasswordResetVerification("a@b.c")
        }
    }

    @Test
    fun `알 수 없는 계정일 때__재설정하면__코드 확인 없이 같은 잘못된 코드 실패를 낸다`() {
        // given
        every { accounts.findLocalUserId(any()) } returns OptionalLong.empty()

        // when
        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("none@b.c", "123456", "NewPass1!") }

        // then
        verify { verifications wasNot Called }
        verify { hashes wasNot Called }
    }

    @Test
    fun `검증이 어떤 방식으로 실패하든__재설정하면__잘못된 코드 하나로 합쳐지고 갱신하지 않는다`() {
        // given
        every { accounts.findLocalUserId(any()) } returns OptionalLong.of(7)
        every { verifications.verifyPasswordResetCode("a@b.c", "000000") } throws InvalidCodeException()
        every { verifications.verifyPasswordResetCode("a@b.c", "111111") } returns false

        // when
        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("a@b.c", "000000", "NewPass1!") }
        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("a@b.c", "111111", "NewPass1!") }

        // then
        verify(exactly = 0) { accounts.updatePassword(any(), any()) }
    }

    @Test
    fun `정리가 실패했을 때__재설정하면__비밀번호는 변경된 채로 남는다`() {
        // given
        every { accounts.findLocalUserId(any()) } returns OptionalLong.of(7)
        every { verifications.verifyPasswordResetCode(any(), any()) } returns true
        every { hashes.encode(any()) } returns "ENC"
        justRun { accounts.updatePassword(any(), any()) }
        every { verifications.clearPasswordResetVerification(any()) } throws IllegalStateException("redis")

        // when
        reset.reset("a@b.c", "123456", "NewPass1!")

        // then
        verify(exactly = 1) { accounts.updatePassword(7, "ENC") }
    }
}
