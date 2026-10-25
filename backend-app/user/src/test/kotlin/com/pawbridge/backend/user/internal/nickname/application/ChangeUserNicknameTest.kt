package com.pawbridge.backend.user.internal.nickname.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class ChangeUserNicknameTest {
    private val users: UserNicknameStore = mock(UserNicknameStore::class.java)
    private val change = ChangeUserNickname(users)

    @Test
    fun given__exact_current_nickname__when__changing__then__lookup_occurs_but_duplicate_query_and_update_are_skipped() {
        `when`(users.findNickname(52L)).thenReturn(Optional.of("Alice"))

        change.change(52L, "Alice")

        verify(users).findNickname(52L)
        verifyNoMoreInteractions(users)
    }

    @Test
    fun given__missing_user__when__changing__then__not_found_precedes_duplicate_query_and_update() {
        `when`(users.findNickname(52L)).thenReturn(Optional.empty())

        assertThrows(UserNotFoundException::class.java) { change.change(52L, "새닉네임") }

        verify(users).findNickname(52L)
        verifyNoMoreInteractions(users)
    }

    @Test
    fun given__different_available_nickname__when__changing__then__lookup_duplicate_check_and_update_use_original_input_in_order() {
        `when`(users.findNickname(52L)).thenReturn(Optional.of("Alice"))
        `when`(users.existsByNickname("새Nick9")).thenReturn(false)

        change.change(52L, "새Nick9")

        val order = inOrder(users)
        order.verify(users).findNickname(52L)
        order.verify(users).existsByNickname("새Nick9")
        order.verify(users).updateNickname(52L, "새Nick9")
        verifyNoMoreInteractions(users)
    }

    @Test
    fun given__nickname_already_used_by_another_user__when__changing__then__duplicate_prevents_update() {
        `when`(users.findNickname(52L)).thenReturn(Optional.of("Alice"))
        `when`(users.existsByNickname("Taken")).thenReturn(true)

        assertThrows(NicknameDuplicateException::class.java) { change.change(52L, "Taken") }

        verify(users).findNickname(52L)
        verify(users).existsByNickname("Taken")
        verifyNoMoreInteractions(users)
    }

    @Test
    fun given__case_only_change_matching_own_database_row__when__changing__then__self_is_not_excluded_from_duplicate_check() {
        `when`(users.findNickname(52L)).thenReturn(Optional.of("Alice"))
        `when`(users.existsByNickname("alice")).thenReturn(true)

        assertThrows(NicknameDuplicateException::class.java) { change.change(52L, "alice") }

        verify(users).findNickname(52L)
        verify(users).existsByNickname("alice")
        verifyNoMoreInteractions(users)
    }

    @Test
    fun given__write_integrity_failure_after_precheck__when__changing__then__failure_is_not_rewritten_as_precheck_duplicate() {
        `when`(users.findNickname(52L)).thenReturn(Optional.of("Alice"))
        `when`(users.existsByNickname("NewNick")).thenReturn(false)
        val failure = DataIntegrityViolationException("synthetic private database failure")
        doThrow(failure).`when`(users).updateNickname(52L, "NewNick")

        assertSame(failure, assertThrows(DataIntegrityViolationException::class.java) { change.change(52L, "NewNick") })
    }
}
