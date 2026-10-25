package com.pawbridge.backend.user.internal.signup.application

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalSignupTest {
    private val verifications: EmailVerifications = mockk<EmailVerifications>()
    private val accounts: SignupAccounts = mockk<SignupAccounts>()
    private val hashes: PasswordHashes = mockk<PasswordHashes>()
    private val signup = LocalSignup(verifications, accounts, hashes)

    @Test
    fun `인증을 마친 새 사용자일 때__가입하면__원래 순서대로 검사하고 인증 정보를 지운다`() {
        // given
        val saved = SignedUp(7, "a@b.c", "Kim", "아주귀여운강아지")

        every { verifications.isVerified(any()) } returns true
        every { accounts.existsLocalEmail(any()) } returns false
        every { hashes.encode(any()) } returns "ENC"
        every { accounts.createLocalUser(any(), any(), any(), any(), any()) } returns saved
        justRun { verifications.clearVerification(any()) }

        // when
        val result = signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)

        // then
        assertEquals(saved, result)
        verifySequence {
            verifications.isVerified("a@b.c")
            accounts.existsLocalEmail("a@b.c")
            hashes.encode("pass12")
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
            verifications.clearVerification("a@b.c")
        }
    }

    @Test
    fun `인증하지 않은 이메일일 때__가입하면__이후 단계를 실행하지 않는다`() {
        // given
        every { verifications.isVerified(any()) } returns false

        // when
        assertThrows(EmailNotVerifiedException::class.java) {
            signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)
        }

        // then
        verify(exactly = 1) { verifications.isVerified("a@b.c") }
        confirmVerified(accounts, hashes)
    }

    @Test
    fun `이메일 중복이거나 비밀번호가 다르거나 허용되지 않는 역할일 때__가입하면__원래 예외를 던지고 계정을 만들지 않는다`() {
        // given
        every { verifications.isVerified("a@b.c") } returns true
        every { accounts.existsLocalEmail("dup@b.c") } returns false
        every { verifications.isVerified("dup@b.c") } returns true
        every { accounts.existsLocalEmail("a@b.c") } returns true

        // when
        assertThrows(EmailDuplicateException::class.java) {
            signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)
        }
        assertThrows(InconsistentPasswordException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", "other1", SignupRole.ROLE_USER, null)
        }
        assertThrows(InconsistentPasswordException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", null, SignupRole.ROLE_USER, null)
        }
        assertThrows(RoleRequiredException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", "pass12", null, null)
        }
        assertThrows(AdminRoleNotAllowedException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_ADMIN, null)
        }
        assertThrows(ShelterApprovalRequiredException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_SHELTER, null)
        }
        assertThrows(ShelterApprovalRequiredException::class.java) {
            signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, "123")
        }

        // then
        verify(exactly = 0) { accounts.createLocalUser(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `보호소 등록번호가 공백뿐일 때__가입하면__여전히 일반 사용자로 가입한다`() {
        // given
        val saved = SignedUp(7, "a@b.c", "Kim", "n")

        every { verifications.isVerified(any()) } returns true
        every { accounts.existsLocalEmail(any()) } returns false
        every { hashes.encode(any()) } returns "ENC"
        every { accounts.createLocalUser(any(), any(), any(), any(), any()) } returns saved
        justRun { verifications.clearVerification(any()) }

        // when
        signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, "  ")

        // then
        verify(exactly = 1) { accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, "  ") }
    }

    @Test
    fun `인증 정보 삭제가 실패할 때__가입하면__계정은 그대로 만들어진다`() {
        // given
        val saved = SignedUp(7, "a@b.c", "Kim", "n")

        every { verifications.isVerified(any()) } returns true
        every { accounts.existsLocalEmail(any()) } returns false
        every { hashes.encode(any()) } returns "ENC"
        every { accounts.createLocalUser(any(), any(), any(), any(), any()) } returns saved
        every { verifications.clearVerification(any()) } throws IllegalStateException("redis down")

        // when
        val result = signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)

        // then
        assertEquals(saved, result)
        verify(exactly = 1) { verifications.clearVerification("a@b.c") }
    }
}
