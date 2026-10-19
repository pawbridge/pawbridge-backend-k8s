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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class CreateNoteBlockTest {
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");

    @ParameterizedTest
    @MethodSource("invalidIds")
    void given__nonpositive_id_including_zero_self__when__creating__then__reject_login_before_self_port_members_or_clock(long current, long target) {
        var members = mock(ContactMemberQuery.class);
        var creation = mock(NoteBlockCreation.class);
        var clock = mock(Clock.class);

        var failure = assertThrows(NoteBlockCreationRejectedException.class,
                () -> new CreateNoteBlock(members, creation, clock).create(current, target));

        assertEquals(NoteBlockCreationRejectedException.Reason.LOGIN, failure.reason());
        verifyNoInteractions(members, creation, clock);
    }

    @Test
    void given__positive_self_target__when__creating__then__reject_self_before_transaction_port_members_or_clock() {
        var members = mock(ContactMemberQuery.class);
        var creation = mock(NoteBlockCreation.class);
        var clock = mock(Clock.class);

        var failure = assertThrows(NoteBlockCreationRejectedException.class,
                () -> new CreateNoteBlock(members, creation, clock).create(7L, 7L));

        assertEquals(NoteBlockCreationRejectedException.Reason.SELF, failure.reason());
        verifyNoInteractions(members, creation, clock);
    }

    @Test
    void given__distinct_active_members__when__creation_calls_supplier__then__lookup_current_target_and_clock_once_in_that_order() {
        var members = mock(ContactMemberQuery.class);
        var creation = mock(NoteBlockCreation.class);
        var clock = mock(Clock.class);
        var events = new ArrayList<String>();
        when(members.get(7L)).thenAnswer(invocation -> {
            events.add("current");
            return new ContactMember(7L, " 原文 ", true, false);
        });
        when(members.get(Long.MAX_VALUE)).thenAnswer(invocation -> {
            events.add("target");
            return new ContactMember(Long.MAX_VALUE, "", true, false);
        });
        when(clock.instant()).thenAnswer(invocation -> { events.add("clock"); return NOW; });
        doAnswer(invocation -> {
            verifyNoInteractions(members, clock);
            events.add("before-supplier");
            Supplier<Instant> supplier = invocation.getArgument(2);
            assertEquals(NOW, supplier.get());
            events.add("after-supplier");
            return null;
        }).when(creation).create(eq(7L), eq(Long.MAX_VALUE), any());

        new CreateNoteBlock(members, creation, clock).create(7L, Long.MAX_VALUE);

        assertEquals(List.of("before-supplier", "current", "target", "clock", "after-supplier"), events);
        verify(creation).create(eq(7L), eq(Long.MAX_VALUE), any());
        verify(members).get(7L);
        verify(members).get(Long.MAX_VALUE);
        verify(clock).instant();
        verifyNoMoreInteractions(members, creation, clock);
    }

    @ParameterizedTest
    @EnumSource(InactiveSide.class)
    void given__inactive_current_or_target__when__supplier_checks_members__then__reject_inactive_without_later_member_clock_or_continuation(InactiveSide side) {
        var members = mock(ContactMemberQuery.class);
        var creation = mock(NoteBlockCreation.class);
        var clock = mock(Clock.class);
        var events = new ArrayList<String>();
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", side != InactiveSide.CURRENT, side == InactiveSide.CURRENT));
        if (side == InactiveSide.TARGET) {
            when(members.get(8L)).thenReturn(new ContactMember(8L, "탈퇴한 회원", false, false));
        }
        doAnswer(invocation -> {
            events.add("before-supplier");
            Supplier<Instant> supplier = invocation.getArgument(2);
            supplier.get();
            events.add("after-supplier");
            return null;
        }).when(creation).create(eq(7L), eq(8L), any());

        var failure = assertThrows(NoteBlockCreationRejectedException.class,
                () -> new CreateNoteBlock(members, creation, clock).create(7L, 8L));

        assertEquals(NoteBlockCreationRejectedException.Reason.INACTIVE, failure.reason());
        assertEquals(List.of("before-supplier"), events);
        verify(creation).create(eq(7L), eq(8L), any());
        verify(members).get(7L);
        if (side == InactiveSide.TARGET) verify(members).get(8L);
        verifyNoInteractions(clock);
        verifyNoMoreInteractions(members, creation);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__current_target_or_clock_failure__when__supplier_runs__then__propagate_same_failure_and_stop_later_work_without_retry(FailurePhase phase) {
        var members = mock(ContactMemberQuery.class);
        var creation = mock(NoteBlockCreation.class);
        var clock = mock(Clock.class);
        RuntimeException failure = phase == FailurePhase.CLOCK ? new IllegalStateException("private clock failure")
                : new ContactMemberUnavailableException(new IllegalStateException("private member failure"));
        if (phase == FailurePhase.CURRENT) when(members.get(7L)).thenThrow(failure);
        else when(members.get(7L)).thenReturn(new ContactMember(7L, "", true, false));
        if (phase == FailurePhase.TARGET) when(members.get(8L)).thenThrow(failure);
        if (phase == FailurePhase.CLOCK) {
            when(members.get(8L)).thenReturn(new ContactMember(8L, "", true, false));
            when(clock.instant()).thenThrow(failure);
        }
        var events = new ArrayList<String>();
        doAnswer(invocation -> {
            events.add("before-supplier");
            Supplier<Instant> supplier = invocation.getArgument(2);
            supplier.get();
            events.add("after-supplier");
            return null;
        }).when(creation).create(eq(7L), eq(8L), any());

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> new CreateNoteBlock(members, creation, clock).create(7L, 8L)));

        assertEquals(List.of("before-supplier"), events);
        verify(creation).create(eq(7L), eq(8L), any());
        verify(members).get(7L);
        if (phase != FailurePhase.CURRENT) verify(members).get(8L);
        if (phase == FailurePhase.CLOCK) verify(clock).instant();
        else verifyNoInteractions(clock);
        verifyNoMoreInteractions(members, creation, clock);
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of(Arguments.of(0L, 8L), Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L), Arguments.of(7L, -1L), Arguments.of(0L, 0L));
    }

    private enum InactiveSide { CURRENT, TARGET }
    private enum FailurePhase { CURRENT, TARGET, CLOCK }
}
