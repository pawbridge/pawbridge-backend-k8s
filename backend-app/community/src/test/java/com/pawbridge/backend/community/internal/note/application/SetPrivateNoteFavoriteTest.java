package com.pawbridge.backend.community.internal.note.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class SetPrivateNoteFavoriteTest {
    private static final UUID NOTE = UUID.fromString("f2700000-0000-0000-0000-000000000001");
    private static final Instant FIRST = Instant.parse("2031-01-01T00:00:00.123456Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__favorite_value_and_active_current__when__setting__then__pass_the_value_and_lookup_only_current_in_callback_with_two_uncached_instants(boolean favorite) {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        var events = new ArrayList<String>();
        when(members.get(7L)).thenAnswer(call -> {
            events.add("current");
            return new ContactMember(7L, " raw ", true, false);
        });
        int[] samples = {0};
        when(clock.instant()).thenAnswer(call -> {
            events.add("clock:" + ++samples[0]);
            return FIRST.plusSeconds(samples[0] - 1);
        });
        // This port models callback placement; it is not transaction or SQL evidence.
        NoteFavoriteMutation mutation = (current, note, requested, guard, now) -> {
            assertEquals(7L, current); assertEquals(NOTE, note); assertEquals(favorite, requested);
            events.add("port"); verifyNoInteractions(members, clock);
            guard.run();
            for (int index = 0; index < 2; index++) assertEquals(FIRST.plusSeconds(index), now.get());
        };

        new SetPrivateNoteFavorite(members, mutation, clock).set(7L, NOTE, favorite);

        assertEquals(List.of("port", "current", "clock:1", "clock:2"), events);
        verify(members).get(7L); verify(clock, times(2)).instant();
        verifyNoMoreInteractions(members, clock);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__nonpositive_direct_caller_and_a_port_failure_before_guard__when__setting__then__preserve_the_earlier_member_sql_failure(long current) {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        var failure = new DataIntegrityViolationException("synthetic member CHECK");
        NoteFavoriteMutation mutation = (actual, note, requested, guard, now) -> {
            assertEquals(current, actual); assertEquals(NOTE, note);
            throw failure;
        };

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> new SetPrivateNoteFavorite(members, mutation, clock).set(current, NOTE, false)));
        verifyNoInteractions(members, clock);
    }

    @Test
    void given__nonpositive_caller_whose_port_reaches_guard__when__checking_current__then__reject_login_inside_callback_before_user_or_clock() {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        int[] entered = {0};
        NoteFavoriteMutation mutation = (current, note, requested, guard, now) -> { entered[0]++; guard.run(); };

        var failure = assertThrows(NoteFavoriteRejectedException.class,
                () -> new SetPrivateNoteFavorite(members, mutation, clock).set(0L, NOTE, false));

        assertEquals(NoteFavoriteRejectedException.Reason.LOGIN, failure.reason());
        assertEquals(1, entered[0]); verifyNoInteractions(members, clock);
    }

    @Test
    void given__inactive_current__when__the_port_invokes_guard__then__reject_inactive_without_sampling_time_or_continuing() {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));
        var events = new ArrayList<String>();
        NoteFavoriteMutation mutation = (current, note, requested, guard, now) -> {
            events.add("port"); guard.run(); events.add("continued"); now.get();
        };

        var failure = assertThrows(NoteFavoriteRejectedException.class,
                () -> new SetPrivateNoteFavorite(members, mutation, clock).set(7L, NOTE, false));

        assertEquals(NoteFavoriteRejectedException.Reason.INACTIVE, failure.reason());
        assertEquals(List.of("port"), events); verify(members).get(7L);
        verifyNoMoreInteractions(members); verifyNoInteractions(clock);
    }

    @Test
    void given__the_port_reports_only_a_missing_owned_note__when__setting__then__map_that_fact_to_not_found_policy() {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        when(members.get(7L)).thenReturn(new ContactMember(7L, "member", true, false));
        when(clock.instant()).thenReturn(FIRST);
        NoteFavoriteMutation mutation = (current, note, requested, guard, now) -> {
            guard.run(); now.get(); throw new NoteFavoriteMutation.MissingOwnedNote();
        };

        var failure = assertThrows(NoteFavoriteRejectedException.class,
                () -> new SetPrivateNoteFavorite(members, mutation, clock).set(7L, NOTE, false));

        assertEquals(NoteFavoriteRejectedException.Reason.NOT_FOUND, failure.reason());
        verify(members).get(7L); verify(clock).instant(); verifyNoMoreInteractions(members, clock);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__user_or_clock_failure__when__the_port_calls_application_dependencies__then__propagate_the_same_failure_without_not_found_translation(FailurePhase phase) {
        var members = mock(ContactMemberQuery.class);
        var clock = mock(Clock.class);
        RuntimeException failure = phase == FailurePhase.USER
                ? new ContactMemberUnavailableException(new IllegalStateException("synthetic user"))
                : new IllegalStateException("synthetic clock");
        if (phase == FailurePhase.USER) when(members.get(7L)).thenThrow(failure);
        else {
            when(members.get(7L)).thenReturn(new ContactMember(7L, "member", true, false));
            when(clock.instant()).thenThrow(failure);
        }
        NoteFavoriteMutation mutation = (current, note, requested, guard, now) -> { guard.run(); now.get(); };

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> new SetPrivateNoteFavorite(members, mutation, clock).set(7L, NOTE, false)));
        verify(members).get(7L);
        if (phase == FailurePhase.USER) verifyNoInteractions(clock); else verify(clock).instant();
        verifyNoMoreInteractions(members, clock);
    }

    private enum FailurePhase { USER, CLOCK }
}
