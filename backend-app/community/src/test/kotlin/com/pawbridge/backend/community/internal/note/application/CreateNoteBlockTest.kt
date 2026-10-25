package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.support.anyNonNull
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier
import java.util.stream.Stream

class CreateNoteBlockTest {
    @ParameterizedTest
    @MethodSource("invalidIds")
    fun given__nonpositive_id_including_zero_self__when__creating__then__reject_login_before_self_port_members_or_clock(
        current: Long,
        target: Long,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val creation = mock(NoteBlockCreation::class.java)
        val clock = mock(Clock::class.java)

        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(current, target)
        }

        assertEquals(NoteBlockCreationRejectedException.Reason.LOGIN, failure.reason())
        verifyNoInteractions(members, creation, clock)
    }

    @Test
    fun given__positive_self_target__when__creating__then__reject_self_before_transaction_port_members_or_clock() {
        val members = mock(ContactMemberQuery::class.java)
        val creation = mock(NoteBlockCreation::class.java)
        val clock = mock(Clock::class.java)

        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(7L, 7L)
        }

        assertEquals(NoteBlockCreationRejectedException.Reason.SELF, failure.reason())
        verifyNoInteractions(members, creation, clock)
    }

    @Test
    fun given__distinct_active_members__when__creation_calls_supplier__then__lookup_current_target_and_clock_once_in_that_order() {
        val members = mock(ContactMemberQuery::class.java)
        val creation = mock(NoteBlockCreation::class.java)
        val clock = mock(Clock::class.java)
        val events = ArrayList<String>()
        whenever(members.get(7L)).thenAnswer {
            events.add("current")
            ContactMember(7L, " 原文 ", true, false)
        }
        whenever(members.get(Long.MAX_VALUE)).thenAnswer {
            events.add("target")
            ContactMember(Long.MAX_VALUE, "", true, false)
        }
        whenever(clock.instant()).thenAnswer {
            events.add("clock")
            NOW
        }
        doAnswer { invocation ->
            verifyNoInteractions(members, clock)
            events.add("before-supplier")
            val supplier = invocation.getArgument<Supplier<Instant>>(2)
            assertEquals(NOW, supplier.get())
            events.add("after-supplier")
            null
        }.`when`(creation).create(eq(7L), eq(Long.MAX_VALUE), anyNonNull<Supplier<Instant>>())

        CreateNoteBlock(members, creation, clock).create(7L, Long.MAX_VALUE)

        assertEquals(listOf("before-supplier", "current", "target", "clock", "after-supplier"), events)
        verify(creation).create(eq(7L), eq(Long.MAX_VALUE), anyNonNull<Supplier<Instant>>())
        verify(members).get(7L)
        verify(members).get(Long.MAX_VALUE)
        verify(clock).instant()
        verifyNoMoreInteractions(members, creation, clock)
    }

    @ParameterizedTest
    @EnumSource(InactiveSide::class)
    fun given__inactive_current_or_target__when__supplier_checks_members__then__reject_inactive_without_later_member_clock_or_continuation(
        side: InactiveSide,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val creation = mock(NoteBlockCreation::class.java)
        val clock = mock(Clock::class.java)
        val events = ArrayList<String>()
        whenever(members.get(7L)).thenReturn(
            ContactMember(7L, "탈퇴한 회원", side != InactiveSide.CURRENT, side == InactiveSide.CURRENT),
        )
        if (side == InactiveSide.TARGET) {
            whenever(members.get(8L)).thenReturn(ContactMember(8L, "탈퇴한 회원", false, false))
        }
        doAnswer { invocation ->
            events.add("before-supplier")
            val supplier = invocation.getArgument<Supplier<Instant>>(2)
            supplier.get()
            events.add("after-supplier")
            null
        }.`when`(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())

        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(7L, 8L)
        }

        assertEquals(NoteBlockCreationRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("before-supplier"), events)
        verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        verify(members).get(7L)
        if (side == InactiveSide.TARGET) {
            verify(members).get(8L)
        }
        verifyNoInteractions(clock)
        verifyNoMoreInteractions(members, creation)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__current_target_or_clock_failure__when__supplier_runs__then__propagate_same_failure_and_stop_later_work_without_retry(
        phase: FailurePhase,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val creation = mock(NoteBlockCreation::class.java)
        val clock = mock(Clock::class.java)
        val failure: RuntimeException =
            if (phase == FailurePhase.CLOCK) {
                IllegalStateException("private clock failure")
            } else {
                ContactMemberUnavailableException(IllegalStateException("private member failure"))
            }
        if (phase == FailurePhase.CURRENT) {
            whenever(members.get(7L)).thenThrow(failure)
        } else {
            whenever(members.get(7L)).thenReturn(ContactMember(7L, "", true, false))
        }
        if (phase == FailurePhase.TARGET) {
            whenever(members.get(8L)).thenThrow(failure)
        }
        if (phase == FailurePhase.CLOCK) {
            whenever(members.get(8L)).thenReturn(ContactMember(8L, "", true, false))
            whenever(clock.instant()).thenThrow(failure)
        }
        val events = ArrayList<String>()
        doAnswer { invocation ->
            events.add("before-supplier")
            val supplier = invocation.getArgument<Supplier<Instant>>(2)
            supplier.get()
            events.add("after-supplier")
            null
        }.`when`(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) {
                CreateNoteBlock(members, creation, clock).create(7L, 8L)
            },
        )

        assertEquals(listOf("before-supplier"), events)
        verify(creation).create(eq(7L), eq(8L), anyNonNull<Supplier<Instant>>())
        verify(members).get(7L)
        if (phase != FailurePhase.CURRENT) {
            verify(members).get(8L)
        }
        if (phase == FailurePhase.CLOCK) {
            verify(clock).instant()
        } else {
            verifyNoInteractions(clock)
        }
        verifyNoMoreInteractions(members, creation, clock)
    }

    enum class InactiveSide { CURRENT, TARGET }

    enum class FailurePhase { CURRENT, TARGET, CLOCK }

    companion object {
        private val NOW = Instant.parse("2031-01-01T00:00:00.123456Z")

        @JvmStatic
        fun invalidIds(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0L, 8L),
                Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L),
                Arguments.of(7L, -1L),
                Arguments.of(0L, 0L),
            )
    }
}
