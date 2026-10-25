package com.pawbridge.backend.user.internal.password.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class ChangeUserPasswordTest {
    private val users: UserPasswordStore = mock(UserPasswordStore::class.java)
    private val hashes: PasswordHashes = mock(PasswordHashes::class.java)
    private val change = ChangeUserPassword(users, hashes)

    @Test
    fun given__local_member_with_matching_current_password__when__changing__then__lookup_match_encode_and_update_run_in_original_order() {
        `when`(users.findCredentials(52L)).thenReturn(Optional.of(StoredCredentials("LOCAL", STORED)))
        `when`(hashes.matches("Current1!", STORED)).thenReturn(true)
        `when`(hashes.encode("NewPass1!")).thenReturn("\$2a\$10\$synthetic.new.hash")

        change.change(52L, "Current1!", "NewPass1!")

        val order = inOrder(users, hashes)
        order.verify(users).findCredentials(52L)
        order.verify(hashes).matches("Current1!", STORED)
        order.verify(hashes).encode("NewPass1!")
        order.verify(users).updatePassword(52L, "\$2a\$10\$synthetic.new.hash")
        verifyNoMoreInteractions(users, hashes)
    }

    @Test
    fun given__missing_member__when__changing__then__not_found_precedes_password_checks_and_update() {
        `when`(users.findCredentials(52L)).thenReturn(Optional.empty())

        assertThrows(UserNotFoundException::class.java) { change.change(52L, "Current1!", "NewPass1!") }

        verify(users).findCredentials(52L)
        verifyNoMoreInteractions(users)
        verifyNoInteractions(hashes)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["GOOGLE", "NAVER", "local", ""])
    fun given__non_local_provider__when__changing__then__social_rejection_precedes_password_matching(provider: String?) {
        `when`(users.findCredentials(52L)).thenReturn(Optional.of(StoredCredentials(provider, null)))

        assertThrows(OAuthUserCannotChangePasswordException::class.java) { change.change(52L, "Current1!", "NewPass1!") }

        verify(users).findCredentials(52L)
        verifyNoMoreInteractions(users)
        verifyNoInteractions(hashes)
    }

    @Test
    fun given__wrong_current_password_even_when_new_equals_current__when__changing__then__current_mismatch_wins_and_nothing_is_written() {
        `when`(users.findCredentials(52L)).thenReturn(Optional.of(StoredCredentials("LOCAL", STORED)))
        `when`(hashes.matches("Wrong1!", STORED)).thenReturn(false)

        assertThrows(InvalidCurrentPasswordException::class.java) { change.change(52L, "Wrong1!", "Wrong1!") }

        verify(users).findCredentials(52L)
        verify(hashes).matches("Wrong1!", STORED)
        verifyNoMoreInteractions(users, hashes)
    }

    @Test
    fun given__new_password_equal_to_matching_current__when__changing__then__same_password_is_rejected_before_encoding() {
        `when`(users.findCredentials(52L)).thenReturn(Optional.of(StoredCredentials("LOCAL", STORED)))
        `when`(hashes.matches("Same1234!", STORED)).thenReturn(true)

        assertThrows(SamePasswordException::class.java) { change.change(52L, "Same1234!", "Same1234!") }

        verify(users).findCredentials(52L)
        verify(hashes).matches("Same1234!", STORED)
        verifyNoMoreInteractions(users, hashes)
    }

    @Test
    fun given__write_failure__when__changing__then__failure_propagates_unchanged() {
        `when`(users.findCredentials(52L)).thenReturn(Optional.of(StoredCredentials("LOCAL", STORED)))
        `when`(hashes.matches("Current1!", STORED)).thenReturn(true)
        `when`(hashes.encode("NewPass1!")).thenReturn("\$2a\$10\$synthetic.new.hash")
        val failure = DataIntegrityViolationException("synthetic private database failure")
        doThrow(failure).`when`(users).updatePassword(52L, "\$2a\$10\$synthetic.new.hash")

        assertSame(
            failure,
            assertThrows(DataIntegrityViolationException::class.java) { change.change(52L, "Current1!", "NewPass1!") },
        )
    }

    @Test
    fun given__stored_credentials__when__rendering__then__hash_is_redacted() {
        val rendered = StoredCredentials("LOCAL", STORED).toString()

        assertTrue(rendered.contains("LOCAL"))
        assertFalse(rendered.contains(STORED))
    }

    companion object {
        private const val STORED = "\$2a\$10\$synthetic.stored.hash.value.for.unit.tests.only"
    }
}
