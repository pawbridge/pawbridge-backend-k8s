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
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.util.stream.Stream

class RemoveNoteBlockTest {
    @ParameterizedTest
    @MethodSource("invalidIds")
    fun given__nonpositive_current_or_target_id__when__removing__then__reject_login_before_transaction_port_or_member_lookup(
        current: Long,
        target: Long,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val removal = mock(NoteBlockRemoval::class.java)
        val service = RemoveNoteBlock(members, removal)

        val failure = assertThrows(NoteBlockRemovalRejectedException::class.java) { service.remove(current, target) }

        assertEquals(NoteBlockRemovalRejectedException.Reason.LOGIN, failure.reason())
        verifyNoInteractions(members, removal)
    }

    @ParameterizedTest
    @ValueSource(longs = [7L, Long.MAX_VALUE])
    fun given__valid_self_or_other_target__when__removing__then__defer_single_current_lookup_to_removal_guard_without_target_lookup(
        target: Long,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val removal = mock(NoteBlockRemoval::class.java)
        val events = ArrayList<String>()
        whenever(members.get(7L)).thenAnswer {
            events.add("current")
            ContactMember(7L, " 原文 ", true, false)
        }
        doAnswer { invocation ->
            events.add("before-guard")
            invocation.getArgument<Runnable>(2).run()
            events.add("after-guard")
            null
        }.`when`(removal).remove(eq(7L), eq(target), anyNonNull<Runnable>())

        RemoveNoteBlock(members, removal).remove(7L, target)

        assertEquals(listOf("before-guard", "current", "after-guard"), events)
        verify(removal).remove(eq(7L), eq(target), anyNonNull<Runnable>())
        verify(members).get(7L)
        verifyNoMoreInteractions(members, removal)
    }

    @Test
    fun given__inactive_current_member__when__removal_runs_guard__then__reject_inactive_and_stop_the_removal_continuation() {
        val members = mock(ContactMemberQuery::class.java)
        val removal = mock(NoteBlockRemoval::class.java)
        val events = ArrayList<String>()
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))
        doAnswer { invocation ->
            events.add("before-guard")
            invocation.getArgument<Runnable>(2).run()
            events.add("after-guard")
            null
        }.`when`(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())

        val failure = assertThrows(NoteBlockRemovalRejectedException::class.java) {
            RemoveNoteBlock(members, removal).remove(7L, 8L)
        }

        assertEquals(NoteBlockRemovalRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("before-guard"), events)
        verify(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())
        verify(members).get(7L)
        verifyNoMoreInteractions(members, removal)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__removal_or_current_lookup_failure__when__removing__then__propagate_same_failure_without_fallback_or_retry(
        phase: FailurePhase,
    ) {
        val members = mock(ContactMemberQuery::class.java)
        val removal = mock(NoteBlockRemoval::class.java)
        val failure: RuntimeException =
            if (phase == FailurePhase.GUARD) {
                ContactMemberUnavailableException(IllegalStateException("private member failure"))
            } else {
                IllegalStateException("private removal failure")
            }
        if (phase != FailurePhase.BEFORE_GUARD) {
            if (phase == FailurePhase.GUARD) {
                whenever(members.get(7L)).thenThrow(failure)
            } else {
                whenever(members.get(7L)).thenReturn(ContactMember(7L, "", true, false))
            }
        }
        doAnswer { invocation ->
            if (phase == FailurePhase.BEFORE_GUARD) throw failure
            invocation.getArgument<Runnable>(2).run()
            throw failure
        }.`when`(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) { RemoveNoteBlock(members, removal).remove(7L, 8L) },
        )

        verify(removal).remove(eq(7L), eq(8L), anyNonNull<Runnable>())
        if (phase == FailurePhase.BEFORE_GUARD) {
            verifyNoInteractions(members)
        } else {
            verify(members).get(7L)
        }
        verifyNoMoreInteractions(members, removal)
    }

    enum class FailurePhase { BEFORE_GUARD, GUARD, AFTER_GUARD }

    companion object {
        @JvmStatic
        fun invalidIds(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0L, 8L),
                Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L),
                Arguments.of(7L, -1L),
            )
    }
}
