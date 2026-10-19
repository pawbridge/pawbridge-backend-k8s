package com.pawbridge.backend.community.internal.note.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class ReadNoteRecipientTest {
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final NoteBlockReader blocks = mock(NoteBlockReader.class);
    private final ReadNoteRecipient query = new ReadNoteRecipient(members, blocks);
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);
    private static final ContactMember RECIPIENT = new ContactMember(99L, "  원문 닉네임  ", true, false);

    @Test
    void given__active_unblocked_members__when__reading__then__return_user_projection_after_current_block_and_target_in_order() {
        when(members.get(7L)).thenReturn(CURRENT);
        when(members.get(99L)).thenReturn(RECIPIENT);

        assertSame(RECIPIENT, query.read(7L, 99L));

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blocked(7L, 99L);
        order.verify(members).get(99L);
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(long currentId) {
        assertEquals(RecipientRejectedException.Reason.LOGIN,
                assertThrows(RecipientRejectedException.class, () -> query.read(currentId, 99L)).reason());

        verifyNoInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_current_member__when__reading_self__then__inactive_precedes_self_and_storage(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(RecipientRejectedException.Reason.INACTIVE,
                assertThrows(RecipientRejectedException.class, () -> query.read(7L, 7L)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks);
    }

    @Test
    void given__active_current_member_addressing_self__when__reading__then__reject_without_block_or_second_user_lookup() {
        when(members.get(7L)).thenReturn(CURRENT);

        assertEquals(RecipientRejectedException.Reason.BLOCKED,
                assertThrows(RecipientRejectedException.class, () -> query.read(7L, 7L)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks);
    }

    @Test
    void given__block_between_members__when__reading__then__reject_before_target_lookup() {
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.blocked(7L, 99L)).thenReturn(true);

        assertEquals(RecipientRejectedException.Reason.BLOCKED,
                assertThrows(RecipientRejectedException.class, () -> query.read(7L, 99L)).reason());

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blocked(7L, 99L);
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__invalid_target_id__when__reading__then__login_rejection_follows_current_and_block_lookup(long targetId) {
        when(members.get(7L)).thenReturn(CURRENT);

        assertEquals(RecipientRejectedException.Reason.LOGIN,
                assertThrows(RecipientRejectedException.class, () -> query.read(7L, targetId)).reason());

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blocked(7L, targetId);
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_target__when__reading__then__reject_only_after_current_and_block_lookup(boolean pending) {
        when(members.get(7L)).thenReturn(CURRENT);
        when(members.get(99L)).thenReturn(new ContactMember(99L, "탈퇴한 회원", false, pending));

        assertEquals(RecipientRejectedException.Reason.INACTIVE,
                assertThrows(RecipientRejectedException.class, () -> query.read(7L, 99L)).reason());

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blocked(7L, 99L);
        order.verify(members).get(99L);
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__lookup_failure__when__reading__then__preserve_origin_and_stop_before_later_validation_or_lookup(FailurePhase phase) {
        when(members.get(7L)).thenReturn(CURRENT);
        long targetId = phase == FailurePhase.BLOCK_DAO || phase == FailurePhase.BLOCK_RUNTIME ? 0L : 99L;
        RuntimeException failure = switch (phase) {
            case CURRENT, TARGET -> new ContactMemberUnavailableException(new IllegalStateException("private User failure"));
            case BLOCK_DAO -> new DataAccessResourceFailureException("private block SQL failure");
            case BLOCK_RUNTIME -> new IllegalStateException("private block runtime");
        };
        switch (phase) {
            case CURRENT -> when(members.get(7L)).thenThrow(failure);
            case TARGET -> when(members.get(99L)).thenThrow(failure);
            case BLOCK_DAO, BLOCK_RUNTIME -> when(blocks.blocked(7L, targetId)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, targetId)));

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        if (phase != FailurePhase.CURRENT) order.verify(blocks).blocked(7L, targetId);
        if (phase == FailurePhase.TARGET) order.verify(members).get(99L);
        verifyNoMoreInteractions(members, blocks);
    }

    private enum FailurePhase { CURRENT, BLOCK_DAO, BLOCK_RUNTIME, TARGET }
}
