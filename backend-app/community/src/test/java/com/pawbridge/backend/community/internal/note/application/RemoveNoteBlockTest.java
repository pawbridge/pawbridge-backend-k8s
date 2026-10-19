package com.pawbridge.backend.community.internal.note.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RemoveNoteBlockTest {
    @ParameterizedTest
    @MethodSource("invalidIds")
    void given__nonpositive_current_or_target_id__when__removing__then__reject_login_before_transaction_port_or_member_lookup(long current, long target) {
        var members = mock(ContactMemberQuery.class);
        var removal = mock(NoteBlockRemoval.class);
        var service = new RemoveNoteBlock(members, removal);

        var failure = assertThrows(NoteBlockRemovalRejectedException.class, () -> service.remove(current, target));

        assertEquals(NoteBlockRemovalRejectedException.Reason.LOGIN, failure.reason());
        verifyNoInteractions(members, removal);
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, Long.MAX_VALUE})
    void given__valid_self_or_other_target__when__removing__then__defer_single_current_lookup_to_removal_guard_without_target_lookup(long target) {
        var members = mock(ContactMemberQuery.class);
        var removal = mock(NoteBlockRemoval.class);
        var events = new ArrayList<String>();
        when(members.get(7L)).thenAnswer(invocation -> {
            events.add("current");
            return new ContactMember(7L, " 原文 ", true, false);
        });
        doAnswer(invocation -> {
            events.add("before-guard");
            invocation.getArgument(2, Runnable.class).run();
            events.add("after-guard");
            return null;
        }).when(removal).remove(eq(7L), eq(target), any(Runnable.class));

        new RemoveNoteBlock(members, removal).remove(7L, target);

        assertEquals(List.of("before-guard", "current", "after-guard"), events);
        verify(removal).remove(eq(7L), eq(target), any(Runnable.class));
        verify(members).get(7L);
        verifyNoMoreInteractions(members, removal);
    }

    @Test
    void given__inactive_current_member__when__removal_runs_guard__then__reject_inactive_and_stop_the_removal_continuation() {
        var members = mock(ContactMemberQuery.class);
        var removal = mock(NoteBlockRemoval.class);
        var events = new ArrayList<String>();
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));
        doAnswer(invocation -> {
            events.add("before-guard");
            invocation.getArgument(2, Runnable.class).run();
            events.add("after-guard");
            return null;
        }).when(removal).remove(eq(7L), eq(8L), any(Runnable.class));

        var failure = assertThrows(NoteBlockRemovalRejectedException.class,
                () -> new RemoveNoteBlock(members, removal).remove(7L, 8L));

        assertEquals(NoteBlockRemovalRejectedException.Reason.INACTIVE, failure.reason());
        assertEquals(List.of("before-guard"), events);
        verify(removal).remove(eq(7L), eq(8L), any(Runnable.class));
        verify(members).get(7L);
        verifyNoMoreInteractions(members, removal);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__removal_or_current_lookup_failure__when__removing__then__propagate_same_failure_without_fallback_or_retry(FailurePhase phase) {
        var members = mock(ContactMemberQuery.class);
        var removal = mock(NoteBlockRemoval.class);
        RuntimeException failure = phase == FailurePhase.GUARD
                ? new ContactMemberUnavailableException(new IllegalStateException("private member failure"))
                : new IllegalStateException("private removal failure");
        if (phase != FailurePhase.BEFORE_GUARD) {
            if (phase == FailurePhase.GUARD) when(members.get(7L)).thenThrow(failure);
            else when(members.get(7L)).thenReturn(new ContactMember(7L, "", true, false));
        }
        doAnswer(invocation -> {
            if (phase == FailurePhase.BEFORE_GUARD) throw failure;
            invocation.getArgument(2, Runnable.class).run();
            throw failure;
        }).when(removal).remove(eq(7L), eq(8L), any(Runnable.class));

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> new RemoveNoteBlock(members, removal).remove(7L, 8L)));

        verify(removal).remove(eq(7L), eq(8L), any(Runnable.class));
        if (phase == FailurePhase.BEFORE_GUARD) verifyNoInteractions(members);
        else verify(members).get(7L);
        verifyNoMoreInteractions(members, removal);
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of(Arguments.of(0L, 8L), Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L), Arguments.of(7L, -1L));
    }

    private enum FailurePhase { BEFORE_GUARD, GUARD, AFTER_GUARD }
}
