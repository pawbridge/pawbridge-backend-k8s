package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class DeletePrivateNoteTest {
    @Test
    fun given__active_current__when__deleting__then__lookup_only_current_in_callback_with_two_uncached_instants() {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        val events = ArrayList<String>()
        whenever(members.get(7L)).thenAnswer {
            events.add("current")
            ContactMember(7L, " raw ", true, false)
        }
        var samples = 0
        whenever(clock.instant()).thenAnswer {
            samples++
            events.add("clock:$samples")
            FIRST.plusSeconds((samples - 1).toLong())
        }
        // This port models callback placement; it is not transaction or SQL evidence.
        val mutation = NoteDeletion { current, note, guard, now ->
            assertEquals(7L, current)
            assertEquals(NOTE, note)
            events.add("port")
            verifyNoInteractions(members, clock)
            guard.run()
            for (index in 0 until 2) {
                assertEquals(FIRST.plusSeconds(index.toLong()), now.get())
            }
        }

        DeletePrivateNote(members, mutation, clock).delete(7L, NOTE)

        assertEquals(listOf("port", "current", "clock:1", "clock:2"), events)
        verify(members).get(7L)
        verify(clock, times(2)).instant()
        verifyNoMoreInteractions(members, clock)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__nonpositive_direct_caller_and_a_port_failure_before_guard__when__deleting__then__preserve_the_earlier_member_sql_failure(
        current: Long,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        val failure = DataIntegrityViolationException("synthetic member CHECK")
        val mutation = NoteDeletion { actual, note, _, _ ->
            assertEquals(current, actual)
            assertEquals(NOTE, note)
            throw failure
        }

        assertSame(
            failure,
            assertThrows(DataIntegrityViolationException::class.java) {
                DeletePrivateNote(members, mutation, clock).delete(current, NOTE)
            },
        )
        verifyNoInteractions(members, clock)
    }

    @Test
    fun given__nonpositive_caller_whose_port_reaches_guard__when__checking_current__then__reject_login_inside_callback_before_user_or_clock() {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        var entered = 0
        val mutation = NoteDeletion { _, _, guard, _ ->
            entered++
            guard.run()
        }

        val failure = assertThrows(NoteDeletionRejectedException::class.java) {
            DeletePrivateNote(members, mutation, clock).delete(0L, NOTE)
        }

        assertEquals(NoteDeletionRejectedException.Reason.LOGIN, failure.reason())
        assertEquals(1, entered)
        verifyNoInteractions(members, clock)
    }

    @Test
    fun given__inactive_current__when__the_port_invokes_guard__then__reject_inactive_without_sampling_time_or_continuing() {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))
        val events = ArrayList<String>()
        val mutation = NoteDeletion { _, _, guard, now ->
            events.add("port")
            guard.run()
            events.add("continued")
            now.get()
        }

        val failure = assertThrows(NoteDeletionRejectedException::class.java) {
            DeletePrivateNote(members, mutation, clock).delete(7L, NOTE)
        }

        assertEquals(NoteDeletionRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("port"), events)
        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock)
    }

    @Test
    fun given__the_port_reports_only_a_missing_owned_note__when__deleting__then__map_that_fact_to_not_found_policy() {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "member", true, false))
        whenever(clock.instant()).thenReturn(FIRST)
        val mutation = NoteDeletion { _, _, guard, now ->
            guard.run()
            now.get()
            throw NoteDeletion.MissingOwnedNote()
        }

        val failure = assertThrows(NoteDeletionRejectedException::class.java) {
            DeletePrivateNote(members, mutation, clock).delete(7L, NOTE)
        }

        assertEquals(NoteDeletionRejectedException.Reason.NOT_FOUND, failure.reason())
        verify(members).get(7L)
        verify(clock).instant()
        verifyNoMoreInteractions(members, clock)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__user_or_clock_failure__when__the_port_calls_application_dependencies__then__propagate_the_same_failure_without_not_found_translation(
        phase: FailurePhase,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val clock = mock(Clock::class.java)
        val failure: RuntimeException =
            if (phase == FailurePhase.USER) {
                ContactMemberUnavailableException(IllegalStateException("synthetic user"))
            } else {
                IllegalStateException("synthetic clock")
            }
        if (phase == FailurePhase.USER) {
            whenever(members.get(7L)).thenThrow(failure)
        } else {
            whenever(members.get(7L)).thenReturn(ContactMember(7L, "member", true, false))
            whenever(clock.instant()).thenThrow(failure)
        }
        val mutation = NoteDeletion { _, _, guard, now ->
            guard.run()
            now.get()
        }

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) {
                DeletePrivateNote(members, mutation, clock).delete(7L, NOTE)
            },
        )
        verify(members).get(7L)
        if (phase == FailurePhase.USER) {
            verifyNoInteractions(clock)
        } else {
            verify(clock).instant()
        }
        verifyNoMoreInteractions(members, clock)
    }

    enum class FailurePhase { USER, CLOCK }

    private companion object {
        val NOTE: UUID = UUID.fromString("f2800000-0000-0000-0000-000000000001")
        val FIRST: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")
    }
}
