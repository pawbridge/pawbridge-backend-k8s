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
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException

class ReadNoteRecipientTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val blocks = mock(NoteBlockReader::class.java)
    private val query = ReadNoteRecipient(members, blocks)

    @Test
    fun given__active_unblocked_members__when__reading__then__return_user_projection_after_current_block_and_target_in_order() {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(members.get(99L)).thenReturn(RECIPIENT)

        assertSame(RECIPIENT, query.read(7L, 99L))

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blocked(7L, 99L)
        order.verify(members).get(99L)
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(currentId: Long) {
        assertEquals(
            RecipientRejectedException.Reason.LOGIN,
            assertThrows(RecipientRejectedException::class.java) { query.read(currentId, 99L) }.reason(),
        )

        verifyNoInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_current_member__when__reading_self__then__inactive_precedes_self_and_storage(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            RecipientRejectedException.Reason.INACTIVE,
            assertThrows(RecipientRejectedException::class.java) { query.read(7L, 7L) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks)
    }

    @Test
    fun given__active_current_member_addressing_self__when__reading__then__reject_without_block_or_second_user_lookup() {
        whenever(members.get(7L)).thenReturn(CURRENT)

        assertEquals(
            RecipientRejectedException.Reason.BLOCKED,
            assertThrows(RecipientRejectedException::class.java) { query.read(7L, 7L) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks)
    }

    @Test
    fun given__block_between_members__when__reading__then__reject_before_target_lookup() {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.blocked(7L, 99L)).thenReturn(true)

        assertEquals(
            RecipientRejectedException.Reason.BLOCKED,
            assertThrows(RecipientRejectedException::class.java) { query.read(7L, 99L) }.reason(),
        )

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blocked(7L, 99L)
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__invalid_target_id__when__reading__then__login_rejection_follows_current_and_block_lookup(targetId: Long) {
        whenever(members.get(7L)).thenReturn(CURRENT)

        assertEquals(
            RecipientRejectedException.Reason.LOGIN,
            assertThrows(RecipientRejectedException::class.java) { query.read(7L, targetId) }.reason(),
        )

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blocked(7L, targetId)
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_target__when__reading__then__reject_only_after_current_and_block_lookup(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(members.get(99L)).thenReturn(ContactMember(99L, "탈퇴한 회원", false, pending))

        assertEquals(
            RecipientRejectedException.Reason.INACTIVE,
            assertThrows(RecipientRejectedException::class.java) { query.read(7L, 99L) }.reason(),
        )

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blocked(7L, 99L)
        order.verify(members).get(99L)
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__lookup_failure__when__reading__then__preserve_origin_and_stop_before_later_validation_or_lookup(
        phase: FailurePhase,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        val targetId = if (phase == FailurePhase.BLOCK_DAO || phase == FailurePhase.BLOCK_RUNTIME) 0L else 99L
        val failure: RuntimeException = when (phase) {
            FailurePhase.CURRENT, FailurePhase.TARGET ->
                ContactMemberUnavailableException(IllegalStateException("private User failure"))
            FailurePhase.BLOCK_DAO -> DataAccessResourceFailureException("private block SQL failure")
            FailurePhase.BLOCK_RUNTIME -> IllegalStateException("private block runtime")
        }
        when (phase) {
            FailurePhase.CURRENT -> whenever(members.get(7L)).thenThrow(failure)
            FailurePhase.TARGET -> whenever(members.get(99L)).thenThrow(failure)
            FailurePhase.BLOCK_DAO, FailurePhase.BLOCK_RUNTIME ->
                whenever(blocks.blocked(7L, targetId)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { query.read(7L, targetId) })

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        if (phase != FailurePhase.CURRENT) {
            order.verify(blocks).blocked(7L, targetId)
        }
        if (phase == FailurePhase.TARGET) {
            order.verify(members).get(99L)
        }
        verifyNoMoreInteractions(members, blocks)
    }

    enum class FailurePhase { CURRENT, BLOCK_DAO, BLOCK_RUNTIME, TARGET }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
        val RECIPIENT = ContactMember(99L, "  원문 닉네임  ", true, false)
    }
}
