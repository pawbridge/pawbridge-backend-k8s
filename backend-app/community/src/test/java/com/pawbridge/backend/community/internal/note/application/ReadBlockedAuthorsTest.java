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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class ReadBlockedAuthorsTest {
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final NoteBlockStatusReader blocks = mock(NoteBlockStatusReader.class);
    private final ReadBlockedAuthors query = new ReadBlockedAuthors(members, blocks);
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);

    @Test
    void given__valid_authors_with_duplicates_self_and_max_id__when__reading__then__check_current_once_and_pass_distinct_ids_without_target_lookups() {
        when(members.get(7L)).thenReturn(CURRENT);
        List<Long> storedIds = List.of(99L, Long.MAX_VALUE);
        when(blocks.blockedAuthors(7L, List.of(Long.MAX_VALUE, 7L, 99L))).thenReturn(storedIds);

        var result = query.read(7L, List.of(Long.MAX_VALUE, 7L, 99L, Long.MAX_VALUE, 99L));

        assertSame(storedIds, result.memberIds());
        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blockedAuthors(7L, List.of(Long.MAX_VALUE, 7L, 99L));
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @EnumSource(InvalidInput.class)
    void given__invalid_original_list_and_current_id__when__reading__then__reject_members_before_login_or_any_lookup(InvalidInput input) {
        List<Long> ids = switch (input) {
            case NULL -> null;
            case EMPTY -> List.of();
            case NULL_ELEMENT -> Arrays.asList(8L, null, 9L);
            case ZERO -> List.of(8L, 0L);
            case NEGATIVE -> List.of(-1L, 8L);
            case DISTINCT_101 -> LongStream.rangeClosed(1, 101).boxed().toList();
            case DUPLICATE_101 -> Collections.nCopies(101, 8L);
        };

        assertEquals(BlockStatusRejectedException.Reason.INVALID_MEMBERS,
                assertThrows(BlockStatusRejectedException.class, () -> query.read(-1L, ids)).reason());

        verifyNoInteractions(members, blocks);
    }

    @ParameterizedTest
    @EnumSource(ValidBoundary.class)
    void given__one_or_one_hundred_original_ids__when__reading__then__allow_boundary_and_deduplicate_only_after_current_lookup(ValidBoundary boundary) {
        List<Long> ids = switch (boundary) {
            case ONE -> List.of(8L);
            case DISTINCT_100 -> LongStream.rangeClosed(1, 100).boxed().toList();
            case DUPLICATE_100 -> Collections.nCopies(100, 8L);
        };
        List<Long> distinctIds = ids.stream().distinct().toList();
        when(members.get(7L)).thenReturn(CURRENT);
        when(blocks.blockedAuthors(7L, distinctIds)).thenReturn(List.of());

        assertEquals(List.of(), query.read(7L, ids).memberIds());

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        order.verify(blocks).blockedAuthors(7L, distinctIds);
        verifyNoMoreInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__valid_list_and_invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(long currentId) {
        assertEquals(BlockStatusRejectedException.Reason.LOGIN,
                assertThrows(BlockStatusRejectedException.class, () -> query.read(currentId, List.of(8L))).reason());

        verifyNoInteractions(members, blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_current_member__when__reading__then__reject_inactive_before_block_lookup(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(BlockStatusRejectedException.Reason.INACTIVE,
                assertThrows(BlockStatusRejectedException.class, () -> query.read(7L, List.of(8L))).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__user_or_block_lookup_failure__when__reading__then__preserve_failure_origin_and_stop_later_lookups(FailurePhase phase) {
        RuntimeException failure = switch (phase) {
            case USER -> new ContactMemberUnavailableException(new DataAccessResourceFailureException("private User SQL"));
            case BLOCK_DAO -> new DataAccessResourceFailureException("private block SQL");
            case BLOCK_RUNTIME -> new IllegalStateException("private block runtime");
        };
        if (phase == FailurePhase.USER) {
            when(members.get(7L)).thenThrow(failure);
        } else {
            when(members.get(7L)).thenReturn(CURRENT);
            when(blocks.blockedAuthors(7L, List.of(8L))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, List.of(8L, 8L))));

        var order = inOrder(members, blocks);
        order.verify(members).get(7L);
        if (phase != FailurePhase.USER) order.verify(blocks).blockedAuthors(7L, List.of(8L));
        verifyNoMoreInteractions(members, blocks);
    }

    private enum InvalidInput { NULL, EMPTY, NULL_ELEMENT, ZERO, NEGATIVE, DISTINCT_101, DUPLICATE_101 }
    private enum ValidBoundary { ONE, DISTINCT_100, DUPLICATE_100 }
    private enum FailurePhase { USER, BLOCK_DAO, BLOCK_RUNTIME }
}
