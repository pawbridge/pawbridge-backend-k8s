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

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

class ListPrivateNotesTest {
    private static final Instant NOW = Instant.parse("2031-01-01T14:59:59.123456Z");
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final PrivateNoteListReader notes = mock(PrivateNoteListReader.class);
    private final ContactMembersQuery contactMembers = mock(ContactMembersQuery.class);
    private final NoteBlockReader blocks = mock(NoteBlockReader.class);
    private final Clock clock = mock(Clock.class);
    private final ListPrivateNotes query = new ListPrivateNotes(members, notes, contactMembers, blocks, clock);

    @ParameterizedTest
    @ValueSource(ints = {-1, 10001})
    void given__invalid_page_box_and_caller__when__listing__then__reject_page_before_box_member_clock_or_sql(int page) {
        assertEquals(PrivateNoteListRejectedException.Reason.INVALID_PAGE,
                assertThrows(PrivateNoteListRejectedException.class, () -> query.read(0L, "invalid", false, page)).reason());

        verifyNoInteractions(members, clock, notes, contactMembers, blocks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inbox", " INBOX"})
    void given__raw_invalid_box_and_caller__when__listing__then__reject_box_without_case_or_whitespace_normalization_before_caller(String box) {
        assertEquals(PrivateNoteListRejectedException.Reason.INVALID_BOX,
                assertThrows(PrivateNoteListRejectedException.class, () -> query.read(0L, box, false, 0)).reason());

        verifyNoInteractions(members, clock, notes, contactMembers, blocks);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__valid_page_and_box_but_invalid_caller__when__listing__then__reject_login_before_dependencies(long caller) {
        assertEquals(PrivateNoteListRejectedException.Reason.LOGIN,
                assertThrows(PrivateNoteListRejectedException.class, () -> query.read(caller, "INBOX", false, 0)).reason());

        verifyNoInteractions(members, clock, notes, contactMembers, blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_caller__when__listing__then__reject_inactive_before_clock_count_list_batch_or_block(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(PrivateNoteListRejectedException.Reason.INACTIVE,
                assertThrows(PrivateNoteListRejectedException.class, () -> query.read(7L, "INBOX", false, 0)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(clock, notes, contactMembers, blocks);
    }

    @ParameterizedTest
    @MethodSource("emptyPages")
    void given__empty_page_with_zero_or_large_count__when__listing__then__still_list_and_preserve_raw_count_requested_page_and_size(long count, int page, int totalPages) {
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7L, "SENT", true, NOW)).thenReturn(count);
        when(notes.read(7L, "SENT", true, page, NOW)).thenReturn(List.of());

        assertEquals(new PrivateNotePage(List.of(), count, totalPages, page, 10), query.read(7L, "SENT", true, page));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).count(7L, "SENT", true, NOW);
        order.verify(notes).read(7L, "SENT", true, page, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(contactMembers, blocks);
    }

    static Stream<Arguments> emptyPages() {
        return Stream.of(Arguments.of(0L, 0, 0), Arguments.of(5_000_000_000L, 10000, 500_000_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INBOX", "SENT"})
    void given__page_with_only_null_counterparts__when__listing__then__keep_notes_and_skip_batch_and_block(String box) {
        Long sender = box.equals("INBOX") ? null : 7L;
        Long recipient = box.equals("INBOX") ? 7L : null;
        StoredNote note = new StoredNote(new UUID(23L, 1L), sender, recipient, " 原文\n본문 ", null, null,
                NOW.minusSeconds(1), box, NOW, true);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7L, box, false, NOW)).thenReturn(1L);
        when(notes.read(7L, box, false, 0, NOW)).thenReturn(List.of(note));

        assertEquals(new PrivateNotePage(List.of(view(note, null, "탈퇴한 회원", false, null)), 1L, 1, 0, 10),
                query.read(7L, box, false, 0));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).count(7L, box, false, NOW);
        order.verify(notes).read(7L, box, false, 0, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(contactMembers, blocks);
    }

    @Test
    void given__mixed_duplicate_null_missing_pending_and_raw_names_after_zero_count__when__listing__then__batch_once_and_block_each_active_inbox_in_content_order_without_pair_cache() {
        StoredNote first = new StoredNote(new UUID(23L, 1L), 8L, 7L, " 원문\n본문 ", "POST", Long.MAX_VALUE,
                NOW.minusSeconds(1), "INBOX", null, true);
        List<StoredNote> found = List.of(first, inbox(2, null), inbox(3, 9L), inbox(4, 8L),
                inbox(5, 10L), inbox(6, 11L), inbox(7, 12L));
        List<Long> ids = List.of(8L, 9L, 10L, 11L, 12L);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7L, "INBOX", false, NOW)).thenReturn(0L);
        when(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(found);
        when(contactMembers.getAll(ids)).thenReturn(List.of(new ContactMember(8L, " 原文 이름 ", true, false),
                new ContactMember(9L, "탈퇴한 회원", false, false), new ContactMember(10L, "탈퇴한 회원", false, true),
                new ContactMember(11L, "", true, false), new ContactMember(12L, "  ", true, false)));
        when(blocks.blocked(7L, 8L)).thenReturn(false, true);
        when(blocks.blocked(7L, 12L)).thenReturn(true);

        List<PrivateNoteView> expected = List.of(view(first, 8L, " 原文 이름 ", true, "/community/" + Long.MAX_VALUE),
                view(found.get(1), null, "탈퇴한 회원", false, null), view(found.get(2), null, "탈퇴한 회원", false, null),
                view(found.get(3), 8L, " 原文 이름 ", false, null), view(found.get(4), null, "탈퇴한 회원", false, null),
                view(found.get(5), 11L, "", true, null), view(found.get(6), 12L, "  ", false, null));
        assertEquals(new PrivateNotePage(expected, 0L, 0, 0, 10), query.read(7L, "INBOX", false, 0));

        var order = inOrder(members, clock, notes, contactMembers, blocks);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).count(7L, "INBOX", false, NOW);
        order.verify(notes).read(7L, "INBOX", false, 0, NOW);
        order.verify(contactMembers).getAll(ids);
        order.verify(blocks, org.mockito.Mockito.times(2)).blocked(7L, 8L);
        order.verify(blocks).blocked(7L, 11L);
        order.verify(blocks).blocked(7L, 12L);
        verifyNoMoreInteractions(members, clock, notes, contactMembers, blocks);
    }

    @Test
    void given__sent_page_with_active_recipient__when__listing__then__display_batch_recipient_report_link_and_page_without_block_lookup() {
        StoredNote note = new StoredNote(new UUID(23L, 8L), 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", 13L,
                NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7L, "SENT", false, NOW)).thenReturn(11L);
        when(notes.read(7L, "SENT", false, 1, NOW)).thenReturn(List.of(note));
        when(contactMembers.getAll(List.of(Long.MAX_VALUE))).thenReturn(List.of(new ContactMember(Long.MAX_VALUE, "수신자", true, false)));

        assertEquals(new PrivateNotePage(List.of(view(note, Long.MAX_VALUE, "수신자", false, "/reports/13")), 11L, 2, 1, 10),
                query.read(7L, "SENT", false, 1));

        var order = inOrder(members, clock, notes, contactMembers);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).count(7L, "SENT", false, NOW);
        order.verify(notes).read(7L, "SENT", false, 1, NOW);
        order.verify(contactMembers).getAll(List.of(Long.MAX_VALUE));
        verifyNoMoreInteractions(members, clock, notes, contactMembers);
        verifyNoInteractions(blocks);
    }

    @Test
    void given__time_advances_during_caller_and_count__when__listing__then__capture_once_after_caller_and_reuse_that_instant_for_list() {
        Instant[] time = {NOW.minusSeconds(1)};
        when(members.get(7L)).thenAnswer(invocation -> {
            time[0] = NOW;
            return CURRENT;
        });
        when(clock.instant()).thenAnswer(invocation -> time[0]);
        when(notes.count(7L, "INBOX", false, NOW)).thenAnswer(invocation -> {
            time[0] = NOW.plusSeconds(3600);
            return 0L;
        });
        when(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(List.of());

        assertEquals(new PrivateNotePage(List.of(), 0L, 0, 0, 10), query.read(7L, "INBOX", false, 0));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).count(7L, "INBOX", false, NOW);
        order.verify(notes).read(7L, "INBOX", false, 0, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(contactMembers, blocks);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__caller_clock_count_list_batch_or_first_or_late_block_failure__when__listing__then__propagate_same_failure_stop_later_steps_and_return_no_partial_page(FailurePhase phase) {
        List<StoredNote> found = List.of(inbox(1, 8L), inbox(2, 9L), inbox(3, 10L));
        List<Long> ids = List.of(8L, 9L, 10L);
        RuntimeException failure = switch (phase) {
            case CALLER -> new ContactMemberUnavailableException(new DataAccessResourceFailureException("private caller SQL"));
            case CLOCK -> new IllegalStateException("private clock");
            case COUNT -> new DataAccessResourceFailureException("private count SQL");
            case LIST -> new CannotCreateTransactionException("private list begin");
            case BATCH -> new ContactMemberUnavailableException(new TransactionSystemException("private batch commit"));
            case FIRST_BLOCK -> new TransactionTimedOutException("private block timeout");
            case LATE_BLOCK -> new TransactionSystemException("private second block commit");
        };
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7L, "INBOX", false, NOW)).thenReturn(3L);
        when(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(found);
        when(contactMembers.getAll(ids)).thenReturn(ids.stream().map(id -> new ContactMember(id, "회원", true, false)).toList());
        switch (phase) {
            case CALLER -> when(members.get(7L)).thenThrow(failure);
            case CLOCK -> when(clock.instant()).thenThrow(failure);
            case COUNT -> when(notes.count(7L, "INBOX", false, NOW)).thenThrow(failure);
            case LIST -> when(notes.read(7L, "INBOX", false, 0, NOW)).thenThrow(failure);
            case BATCH -> when(contactMembers.getAll(ids)).thenThrow(failure);
            case FIRST_BLOCK -> when(blocks.blocked(7L, 8L)).thenThrow(failure);
            case LATE_BLOCK -> when(blocks.blocked(7L, 9L)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, "INBOX", false, 0)));

        var order = inOrder(members, clock, notes, contactMembers, blocks);
        order.verify(members).get(7L);
        if (phase != FailurePhase.CALLER) order.verify(clock).instant();
        if (phase != FailurePhase.CALLER && phase != FailurePhase.CLOCK) order.verify(notes).count(7L, "INBOX", false, NOW);
        if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH || phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK)
            order.verify(notes).read(7L, "INBOX", false, 0, NOW);
        if (phase == FailurePhase.BATCH || phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK)
            order.verify(contactMembers).getAll(ids);
        if (phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK) order.verify(blocks).blocked(7L, 8L);
        if (phase == FailurePhase.LATE_BLOCK) order.verify(blocks).blocked(7L, 9L);
        verifyNoMoreInteractions(members, clock, notes, contactMembers, blocks);
    }

    private static StoredNote inbox(int index, Long senderId) {
        return new StoredNote(new UUID(23L, index), senderId, 7L, " 본문\n" + index + " ", null, null,
                NOW.minusSeconds(index), "INBOX", index % 2 == 0 ? NOW : null, index % 2 == 0);
    }

    private static PrivateNoteView view(StoredNote note, Long counterpartId, String nickname, boolean canReply, String href) {
        return new PrivateNoteView(note.noteId(), note.body(), note.direction(), note.createdAt(), note.readAt(), note.favorite(),
                counterpartId, nickname, canReply, href);
    }

    private enum FailurePhase { CALLER, CLOCK, COUNT, LIST, BATCH, FIRST_BLOCK, LATE_BLOCK }
}
