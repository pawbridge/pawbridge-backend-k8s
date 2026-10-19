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
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

class ReadPrivateNoteTest {
    private static final Instant NOW = Instant.parse("2031-01-01T14:59:59.123456Z");
    private static final UUID NOTE_ID = new UUID(1L, 9L);
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);
    private static final ContactMember COUNTERPART = new ContactMember(8L, " 원문 닉네임 ", true, false);
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final PrivateNoteReader notes = mock(PrivateNoteReader.class);
    private final NoteBlockReader blocks = mock(NoteBlockReader.class);
    private final Clock clock = mock(Clock.class);
    private final ReadPrivateNote query = new ReadPrivateNote(members, notes, blocks, clock);

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__invalid_current_id__when__reading__then__reject_login_before_member_clock_or_sql(long currentUserId) {
        assertEquals(PrivateNoteRejectedException.Reason.LOGIN,
                assertThrows(PrivateNoteRejectedException.class, () -> query.read(currentUserId, NOTE_ID)).reason());

        verifyNoInteractions(members, clock, notes, blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_caller__when__reading__then__reject_inactive_before_clock_find_counterpart_and_block(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(PrivateNoteRejectedException.Reason.INACTIVE,
                assertThrows(PrivateNoteRejectedException.class, () -> query.read(7L, NOTE_ID)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(clock, notes, blocks);
    }

    @Test
    void given__no_visible_note__when__reading__then__reject_not_found_after_caller_and_find_without_counterpart_or_block() {
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.empty());

        assertEquals(PrivateNoteRejectedException.Reason.NOT_FOUND,
                assertThrows(PrivateNoteRejectedException.class, () -> query.read(7L, NOTE_ID)).reason());

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).find(7L, NOTE_ID, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(blocks);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__active_inbox_counterpart_and_block_status__when__reading__then__preserve_ten_fields_and_return_can_reply_without_rejecting_blocked_note(boolean blocked) {
        StoredNote note = new StoredNote(NOTE_ID, 8L, 7L, " 원문\n쪽지 본문 ", "POST", Long.MAX_VALUE,
                NOW.minusSeconds(5), "INBOX", null, true);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note));
        when(members.get(8L)).thenReturn(COUNTERPART);
        when(blocks.blocked(7L, 8L)).thenReturn(blocked);

        assertEquals(new PrivateNoteView(NOTE_ID, " 원문\n쪽지 본문 ", "INBOX", NOW.minusSeconds(5),
                null, true, 8L, " 원문 닉네임 ", !blocked, "/community/" + Long.MAX_VALUE), query.read(7L, NOTE_ID));

        var order = inOrder(members, clock, notes, blocks);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).find(7L, NOTE_ID, NOW);
        order.verify(members).get(8L);
        order.verify(blocks).blocked(7L, 8L);
        verifyNoMoreInteractions(members, clock, notes, blocks);
    }

    @Test
    void given__sent_note_with_active_recipient__when__reading__then__display_recipient_and_report_link_without_block_lookup() {
        StoredNote note = new StoredNote(NOTE_ID, 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", Long.MAX_VALUE,
                NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note));
        when(members.get(Long.MAX_VALUE)).thenReturn(new ContactMember(Long.MAX_VALUE, "수신자", true, false));

        assertEquals(new PrivateNoteView(NOTE_ID, "보낸 원문", "SENT", NOW.minusSeconds(5), NOW.minusSeconds(1),
                false, Long.MAX_VALUE, "수신자", false, "/reports/" + Long.MAX_VALUE), query.read(7L, NOTE_ID));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).find(7L, NOTE_ID, NOW);
        order.verify(members).get(Long.MAX_VALUE);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(blocks);
    }

    @ParameterizedTest
    @EnumSource(CounterpartState.class)
    void given__null_missing_pending_or_raw_empty_counterpart__when__reading__then__apply_withdrawn_policy_and_only_block_check_active_inbox(CounterpartState state) {
        Long senderId = state == CounterpartState.NULL_ID ? null : 8L;
        StoredNote note = new StoredNote(NOTE_ID, senderId, 7L, "본문", null, null, NOW, "INBOX", null, false);
        boolean active = state == CounterpartState.EMPTY_NAME || state == CounterpartState.SPACE_NAME;
        String nickname = state == CounterpartState.EMPTY_NAME ? "" : "  ";
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note));
        if (senderId != null) {
            when(members.get(senderId)).thenReturn(new ContactMember(senderId, nickname, active,
                    state == CounterpartState.PENDING));
        }

        assertEquals(new PrivateNoteView(NOTE_ID, "본문", "INBOX", NOW, null, false,
                active ? 8L : null, active ? nickname : "탈퇴한 회원", active, null), query.read(7L, NOTE_ID));

        var order = inOrder(members, clock, notes, blocks);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).find(7L, NOTE_ID, NOW);
        if (senderId != null) order.verify(members).get(senderId);
        if (active) order.verify(blocks).blocked(7L, 8L);
        verifyNoMoreInteractions(members, clock, notes, blocks);
    }

    @Test
    void given__time_advances_during_caller_and_counterpart__when__reading__then__capture_once_after_caller_and_before_find() {
        Instant[] time = {NOW.minusSeconds(1)};
        when(members.get(7L)).thenAnswer(invocation -> {
            time[0] = NOW;
            return CURRENT;
        });
        when(clock.instant()).thenAnswer(invocation -> time[0]);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(inbox()));
        when(members.get(8L)).thenAnswer(invocation -> {
            time[0] = NOW.plusSeconds(3600);
            return COUNTERPART;
        });

        assertEquals(NOW, query.read(7L, NOTE_ID).createdAt());

        var order = inOrder(members, clock, notes, blocks);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).find(7L, NOTE_ID, NOW);
        order.verify(members).get(8L);
        order.verify(blocks).blocked(7L, 8L);
        verifyNoMoreInteractions(members, clock, notes, blocks);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__caller_clock_find_counterpart_or_block_failure__when__reading__then__propagate_same_failure_and_stop_later_steps(FailurePhase phase) {
        RuntimeException failure = switch (phase) {
            case CALLER -> new ContactMemberUnavailableException(new DataAccessResourceFailureException("private User SQL"));
            case COUNTERPART -> new ContactMemberUnavailableException(new TransactionSystemException("private User commit"));
            case CLOCK -> new IllegalStateException("private clock");
            case FIND -> new DataAccessResourceFailureException("private note SQL");
            case BLOCK -> new TransactionTimedOutException("private block timeout");
        };
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(inbox()));
        when(members.get(8L)).thenReturn(COUNTERPART);
        switch (phase) {
            case CALLER -> when(members.get(7L)).thenThrow(failure);
            case CLOCK -> when(clock.instant()).thenThrow(failure);
            case FIND -> when(notes.find(7L, NOTE_ID, NOW)).thenThrow(failure);
            case COUNTERPART -> when(members.get(8L)).thenThrow(failure);
            case BLOCK -> when(blocks.blocked(7L, 8L)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, NOTE_ID)));

        var order = inOrder(members, clock, notes, blocks);
        order.verify(members).get(7L);
        if (phase != FailurePhase.CALLER) order.verify(clock).instant();
        if (phase == FailurePhase.FIND || phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK)
            order.verify(notes).find(7L, NOTE_ID, NOW);
        if (phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK) order.verify(members).get(8L);
        if (phase == FailurePhase.BLOCK) order.verify(blocks).blocked(7L, 8L);
        verifyNoMoreInteractions(members, clock, notes, blocks);
    }

    private static StoredNote inbox() {
        return new StoredNote(NOTE_ID, 8L, 7L, "본문", null, null, NOW, "INBOX", null, false);
    }

    private enum CounterpartState { NULL_ID, MISSING, PENDING, EMPTY_NAME, SPACE_NAME }
    private enum FailurePhase { CALLER, CLOCK, FIND, COUNTERPART, BLOCK }
}
