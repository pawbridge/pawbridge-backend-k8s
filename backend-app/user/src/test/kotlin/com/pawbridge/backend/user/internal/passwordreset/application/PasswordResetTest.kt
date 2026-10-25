package com.pawbridge.backend.user.internal.passwordreset.application

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.emailverification.application.InvalidCodeException
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.OptionalLong

class PasswordResetTest {
    private val accounts: PasswordResetAccounts = mock(PasswordResetAccounts::class.java)
    private val verifications: EmailVerifications = mock(EmailVerifications::class.java)
    private val hashes: PasswordHashes = mock(PasswordHashes::class.java)
    private val reset = PasswordReset(accounts, verifications, hashes)

    @Test
    fun given__local_account__when__requesting__then__code_is_mailed() {
        `when`(accounts.findLocalUserId("a@b.c")).thenReturn(OptionalLong.of(7))

        reset.request("a@b.c")

        verify(verifications).sendPasswordResetCode("a@b.c")
    }

    @Test
    fun given__unknown_account_or_mail_failure__when__requesting__then__same_silent_success() {
        reset.request("none@b.c")
        verify(verifications, never()).sendPasswordResetCode(anyString())
        `when`(accounts.findLocalUserId("a@b.c")).thenReturn(OptionalLong.of(7))
        doThrow(IllegalStateException("smtp")).`when`(verifications).sendPasswordResetCode("a@b.c")

        reset.request("a@b.c")
    }

    @Test
    fun given__valid_code__when__resetting__then__lookup_verify_encode_update_clear_in_original_order() {
        `when`(accounts.findLocalUserId("a@b.c")).thenReturn(OptionalLong.of(7))
        `when`(verifications.verifyPasswordResetCode("a@b.c", "123456")).thenReturn(true)
        `when`(hashes.encode("NewPass1!")).thenReturn("ENC")

        reset.reset("a@b.c", "123456", "NewPass1!")

        val order = inOrder(accounts, verifications, hashes)
        order.verify(accounts).findLocalUserId("a@b.c")
        order.verify(verifications).verifyPasswordResetCode("a@b.c", "123456")
        order.verify(hashes).encode("NewPass1!")
        order.verify(accounts).updatePassword(7, "ENC")
        order.verify(verifications).clearPasswordResetVerification("a@b.c")
        verifyNoMoreInteractions(accounts, verifications, hashes)
    }

    @Test
    fun given__unknown_account__when__resetting__then__same_invalid_code_failure_without_code_check() {
        `when`(accounts.findLocalUserId("none@b.c")).thenReturn(OptionalLong.empty())

        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("none@b.c", "123456", "NewPass1!") }

        verifyNoInteractions(verifications, hashes)
    }

    @Test
    fun given__any_verification_failure__when__resetting__then__collapsed_to_invalid_code_and_no_update() {
        `when`(accounts.findLocalUserId("a@b.c")).thenReturn(OptionalLong.of(7))
        `when`(verifications.verifyPasswordResetCode("a@b.c", "000000")).thenThrow(InvalidCodeException())
        `when`(verifications.verifyPasswordResetCode("a@b.c", "111111")).thenReturn(false)

        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("a@b.c", "000000", "NewPass1!") }
        assertThrows(PasswordResetCodeInvalidException::class.java) { reset.reset("a@b.c", "111111", "NewPass1!") }

        verify(accounts, never()).updatePassword(anyLong(), anyString())
    }

    @Test
    fun given__failed_cleanup__when__resetting__then__password_stays_changed() {
        `when`(accounts.findLocalUserId("a@b.c")).thenReturn(OptionalLong.of(7))
        `when`(verifications.verifyPasswordResetCode("a@b.c", "123456")).thenReturn(true)
        `when`(hashes.encode("NewPass1!")).thenReturn("ENC")
        doThrow(IllegalStateException("redis")).`when`(verifications).clearPasswordResetVerification("a@b.c")

        reset.reset("a@b.c", "123456", "NewPass1!")

        verify(accounts).updatePassword(7, "ENC")
    }
}
