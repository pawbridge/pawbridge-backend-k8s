package com.pawbridge.backend.user.internal.signup.application

import com.pawbridge.backend.user.anyNonNull
import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class LocalSignupTest {
    private val verifications: EmailVerifications = mock(EmailVerifications::class.java)
    private val accounts: SignupAccounts = mock(SignupAccounts::class.java)
    private val hashes: PasswordHashes = mock(PasswordHashes::class.java)
    private val signup = LocalSignup(verifications, accounts, hashes)

    @Test
    fun given__verified_new_user__when__signing_up__then__checks_run_in_original_order_and_verification_is_cleared() {
        `when`(verifications.isVerified("a@b.c")).thenReturn(true)
        `when`(hashes.encode("pass12")).thenReturn("ENC")
        val saved = SignedUp(7, "a@b.c", "Kim", "아주귀여운강아지")
        `when`(accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)).thenReturn(saved)

        assertEquals(saved, signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null))

        val order = inOrder(verifications, accounts, hashes)
        order.verify(verifications).isVerified("a@b.c")
        order.verify(accounts).existsLocalEmail("a@b.c")
        order.verify(hashes).encode("pass12")
        order.verify(accounts).createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        order.verify(verifications).clearVerification("a@b.c")
        verifyNoMoreInteractions(verifications, accounts, hashes)
    }

    @Test
    fun given__unverified_email__when__signing_up__then__nothing_else_runs() {
        assertThrows(EmailNotVerifiedException::class.java) {
            signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null)
        }

        verifyNoInteractions(accounts, hashes)
    }

    @Test
    fun given__duplicate_mismatch_or_forbidden_role__when__signing_up__then__original_exception_and_no_insert() {
        `when`(verifications.isVerified("a@b.c")).thenReturn(true)
        `when`(accounts.existsLocalEmail("dup@b.c")).thenReturn(false)
        `when`(verifications.isVerified("dup@b.c")).thenReturn(true)
        `when`(accounts.existsLocalEmail("a@b.c")).thenReturn(true)

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
        verify(accounts, never()).createLocalUser(anyString(), anyString(), anyString(), anyNonNull<SignupRole>(), any())
    }

    @Test
    fun given__blank_care_registration_number__when__signing_up__then__still_a_plain_user() {
        `when`(verifications.isVerified("a@b.c")).thenReturn(true)
        `when`(hashes.encode("pass12")).thenReturn("ENC")

        signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, "  ")

        verify(accounts).createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, "  ")
    }

    @Test
    fun given__failed_verification_cleanup__when__signing_up__then__account_is_still_created() {
        `when`(verifications.isVerified("a@b.c")).thenReturn(true)
        `when`(hashes.encode("pass12")).thenReturn("ENC")
        val saved = SignedUp(7, "a@b.c", "Kim", "n")
        `when`(accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)).thenReturn(saved)
        doThrow(IllegalStateException("redis down")).`when`(verifications).clearVerification("a@b.c")

        assertEquals(saved, signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null))
    }
}
