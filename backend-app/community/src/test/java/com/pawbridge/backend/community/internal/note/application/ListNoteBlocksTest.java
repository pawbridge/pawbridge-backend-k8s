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

import com.pawbridge.backend.community.internal.note.application.BlockPage.BlockView;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionTimedOutException;

class ListNoteBlocksTest {
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final NoteBlockListReader blocks = mock(NoteBlockListReader.class);
    private final ContactMembersQuery contactMembers = mock(ContactMembersQuery.class);
    private final ListNoteBlocks query = new ListNoteBlocks(members, blocks, contactMembers);

    @ParameterizedTest
    @ValueSource(ints = {-1, 10001})
    void given__invalid_page_and_current_id__when__reading__then__reject_page_before_login_or_any_dependency(int page) {
        assertEquals(NoteBlockListRejectedException.Reason.INVALID_PAGE,
                assertThrows(NoteBlockListRejectedException.class, () -> query.read(0L, page)).reason());

        verifyNoInteractions(members, blocks, contactMembers);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10000})
    void given__valid_page_boundary_beyond_content__when__reading__then__keep_requested_page_count_and_order_without_batch(int page) {
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.count(7L)).thenReturn(11L);
        when(blocks.read(7L, page)).thenReturn(List.of());

        assertEquals(new BlockPage(List.of(), 11L, 2, page), query.read(7L, page));

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).count(7L);
        order.verify(blocks).read(7L, page);
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(contactMembers);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__valid_page_and_invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(long currentUserId) {
        assertEquals(NoteBlockListRejectedException.Reason.LOGIN,
                assertThrows(NoteBlockListRejectedException.class, () -> query.read(currentUserId, 0)).reason());

        verifyNoInteractions(members, blocks, contactMembers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_current_member__when__reading__then__reject_inactive_before_count_list_or_batch(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(NoteBlockListRejectedException.Reason.INACTIVE,
                assertThrows(NoteBlockListRejectedException.class, () -> query.read(7L, 0)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks, contactMembers);
    }

    @Test
    void given__ordered_blocks_and_unordered_complete_contacts__when__reading__then__batch_once_and_preserve_target_ids_raw_names_instants_and_page() {
        Instant first = Instant.parse("2026-10-19T14:59:59.123456Z");
        Instant second = Instant.parse("1969-12-31T23:59:59.000001Z");
        List<Block> page = List.of(new Block(90L, first), new Block(80L, first), new Block(70L, first),
                new Block(60L, second), new Block(50L, second));
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.count(7L)).thenReturn(15L);
        when(blocks.read(7L, 1)).thenReturn(page);
        when(contactMembers.getAll(List.of(90L, 80L, 70L, 60L, 50L))).thenReturn(List.of(
                new ContactMember(50L, "탈퇴한 회원", false, true),
                new ContactMember(60L, "탈퇴한 회원", false, false),
                new ContactMember(70L, "  ", true, false),
                new ContactMember(80L, "", true, false),
                new ContactMember(90L, " 원문 닉네임 ", true, false)));

        assertEquals(new BlockPage(List.of(new BlockView(90L, " 원문 닉네임 ", first), new BlockView(80L, "", first),
                new BlockView(70L, "  ", first), new BlockView(60L, "탈퇴한 회원", second),
                new BlockView(50L, "탈퇴한 회원", second)), 15L, 2, 1), query.read(7L, 1));

        var order = inOrder(members, blocks, contactMembers);
        order.verify(members).get(7L);
        order.verify(blocks).count(7L);
        order.verify(blocks).read(7L, 1);
        order.verify(contactMembers).getAll(List.of(90L, 80L, 70L, 60L, 50L));
        verifyNoMoreInteractions(members, blocks, contactMembers);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 17L})
    void given__empty_page_including_zero_count__when__reading__then__still_read_the_page_once_and_skip_only_batch(long count) {
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.count(7L)).thenReturn(count);
        when(blocks.read(7L, 1)).thenReturn(List.of());

        assertEquals(new BlockPage(List.of(), count, count == 0 ? 0 : 2, 1), query.read(7L, 1));

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).count(7L);
        order.verify(blocks).read(7L, 1);
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(contactMembers);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__caller_count_list_or_batch_failure__when__reading__then__preserve_failure_origin_and_stop_later_steps(FailurePhase phase) {
        RuntimeException failure = switch (phase) {
            case CALLER, BATCH -> new ContactMemberUnavailableException(new DataAccessResourceFailureException("private User SQL"));
            case COUNT -> new DataAccessResourceFailureException("private count SQL");
            case PAGE -> new TransactionTimedOutException("private list timeout");
        };
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.count(7L)).thenReturn(1L);
        when(blocks.read(7L, 0)).thenReturn(List.of(new Block(8L, Instant.EPOCH)));
        switch (phase) {
            case CALLER -> when(members.get(7L)).thenThrow(failure);
            case COUNT -> when(blocks.count(7L)).thenThrow(failure);
            case PAGE -> when(blocks.read(7L, 0)).thenThrow(failure);
            case BATCH -> when(contactMembers.getAll(List.of(8L))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, 0)));

        var order = inOrder(members, blocks, contactMembers);
        order.verify(members).get(7L);
        if (phase != FailurePhase.CALLER) order.verify(blocks).count(7L);
        if (phase == FailurePhase.PAGE || phase == FailurePhase.BATCH) order.verify(blocks).read(7L, 0);
        if (phase == FailurePhase.BATCH) order.verify(contactMembers).getAll(List.of(8L));
        verifyNoMoreInteractions(members, blocks, contactMembers);
    }

    private enum FailurePhase { CALLER, COUNT, PAGE, BATCH }
}
