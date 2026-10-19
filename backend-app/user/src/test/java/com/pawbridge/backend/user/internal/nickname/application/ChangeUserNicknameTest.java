package com.pawbridge.backend.user.internal.nickname.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ChangeUserNicknameTest {
    private final UserNicknameStore users = mock(UserNicknameStore.class);
    private final ChangeUserNickname change = new ChangeUserNickname(users);

    @Test
    void given__exact_current_nickname__when__changing__then__lookup_occurs_but_duplicate_query_and_update_are_skipped() {
        when(users.findNickname(52L)).thenReturn(Optional.of("Alice"));

        change.change(52L, "Alice");

        verify(users).findNickname(52L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void given__missing_user__when__changing__then__not_found_precedes_duplicate_query_and_update() {
        when(users.findNickname(52L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> change.change(52L, "새닉네임"));

        verify(users).findNickname(52L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void given__different_available_nickname__when__changing__then__lookup_duplicate_check_and_update_use_original_input_in_order() {
        when(users.findNickname(52L)).thenReturn(Optional.of("Alice"));
        when(users.existsByNickname("새Nick9")).thenReturn(false);

        change.change(52L, "새Nick9");

        var order = inOrder(users);
        order.verify(users).findNickname(52L);
        order.verify(users).existsByNickname("새Nick9");
        order.verify(users).updateNickname(52L, "새Nick9");
        verifyNoMoreInteractions(users);
    }

    @Test
    void given__nickname_already_used_by_another_user__when__changing__then__duplicate_prevents_update() {
        when(users.findNickname(52L)).thenReturn(Optional.of("Alice"));
        when(users.existsByNickname("Taken")).thenReturn(true);

        assertThrows(NicknameDuplicateException.class, () -> change.change(52L, "Taken"));

        verify(users).findNickname(52L);
        verify(users).existsByNickname("Taken");
        verifyNoMoreInteractions(users);
    }

    @Test
    void given__case_only_change_matching_own_database_row__when__changing__then__self_is_not_excluded_from_duplicate_check() {
        when(users.findNickname(52L)).thenReturn(Optional.of("Alice"));
        when(users.existsByNickname("alice")).thenReturn(true);

        assertThrows(NicknameDuplicateException.class, () -> change.change(52L, "alice"));

        verify(users).findNickname(52L);
        verify(users).existsByNickname("alice");
        verifyNoMoreInteractions(users);
    }

    @Test
    void given__write_integrity_failure_after_precheck__when__changing__then__failure_is_not_rewritten_as_precheck_duplicate() {
        when(users.findNickname(52L)).thenReturn(Optional.of("Alice"));
        when(users.existsByNickname("NewNick")).thenReturn(false);
        var failure = new DataIntegrityViolationException("synthetic private database failure");
        org.mockito.Mockito.doThrow(failure).when(users).updateNickname(52L, "NewNick");

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> change.change(52L, "NewNick")));
    }
}
