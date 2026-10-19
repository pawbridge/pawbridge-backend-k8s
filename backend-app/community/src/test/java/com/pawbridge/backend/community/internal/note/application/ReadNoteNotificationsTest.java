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

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row;
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class ReadNoteNotificationsTest {
    private static final Instant NOW = Instant.parse("2031-01-01T14:59:59.123456Z");
    private static final UUID CURSOR = new UUID(1L, 9L);
    private static final ContactMember CURRENT = new ContactMember(7L, "현재 회원", true, false);
    private final ContactMemberQuery members = mock(ContactMemberQuery.class);
    private final NoteNotificationReader notes = mock(NoteNotificationReader.class);
    private final ContactMembersQuery contactMembers = mock(ContactMembersQuery.class);
    private final Clock clock = mock(Clock.class);
    private final ReadNoteNotifications query = new ReadNoteNotifications(members, notes, contactMembers, clock);

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void given__invalid_current_id__when__reading__then__reject_login_before_member_clock_or_sql(long currentUserId) {
        assertEquals(NoteNotificationsRejectedException.Reason.LOGIN,
                assertThrows(NoteNotificationsRejectedException.class, () -> query.read(currentUserId, CURSOR)).reason());

        verifyNoInteractions(members, clock, notes, contactMembers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__missing_or_pending_caller__when__reading__then__reject_inactive_before_clock_list_batch_and_unread(boolean pending) {
        when(members.get(7L)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, pending));

        assertEquals(NoteNotificationsRejectedException.Reason.INACTIVE,
                assertThrows(NoteNotificationsRejectedException.class, () -> query.read(7L, CURSOR)).reason());

        verify(members).get(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(clock, notes, contactMembers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__empty_or_only_null_senders__when__reading__then__skip_batch_but_return_last_unread_raw_count(boolean nullSender) {
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        Row row = row(1, null, null);
        when(notes.read(7L, CURSOR, NOW)).thenReturn(nullSender ? List.of(row) : List.of());
        when(notes.unread(7L, NOW)).thenReturn(Long.MAX_VALUE);

        List<Notification> content = nullSender
                ? List.of(new Notification(row.noteId(), "PRIVATE_NOTE", null, "탈퇴한 회원", NOW, false,
                        "/notes/" + row.noteId())) : List.of();
        assertEquals(new NoteNotifications(content, null, Long.MAX_VALUE), query.read(7L, CURSOR));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).read(7L, CURSOR, NOW);
        order.verify(notes).unread(7L, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(contactMembers);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 21})
    void given__twenty_or_twenty_one_rows__when__reading__then__batch_only_displayed_twenty_and_use_last_displayed_note_as_next_cursor(int size) {
        List<Row> found = IntStream.range(0, size).mapToObj(index -> row(index, 100L + index,
                index % 2 == 0 ? null : NOW.minusSeconds(1))).toList();
        List<Long> ids = IntStream.range(0, 20).mapToObj(index -> 100L + index).toList();
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.read(7L, null, NOW)).thenReturn(found);
        when(contactMembers.getAll(ids)).thenReturn(ids.stream()
                .map(id -> new ContactMember(id, "회원" + id, true, false)).toList());
        when(notes.unread(7L, NOW)).thenReturn(31L);

        List<Notification> expected = found.subList(0, 20).stream().map(row -> new Notification(
                row.noteId(), "PRIVATE_NOTE", row.senderId(), "회원" + row.senderId(), row.createdAt(),
                row.readAt() != null, "/notes/" + row.noteId())).toList();
        assertEquals(new NoteNotifications(expected, size == 21 ? found.get(19).noteId() : null, 31L),
                query.read(7L, null));

        var order = inOrder(members, clock, notes, contactMembers);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).read(7L, null, NOW);
        order.verify(contactMembers).getAll(ids);
        order.verify(notes).unread(7L, NOW);
        verifyNoMoreInteractions(members, clock, notes, contactMembers);
    }

    @Test
    void given__duplicate_null_active_missing_and_pending_senders__when__reading__then__distinct_once_preserve_order_raw_names_and_nullable_actor_ids() {
        List<Row> found = List.of(row(1, 90L, null), row(2, null, NOW), row(3, 80L, null),
                row(4, 90L, NOW), row(5, 70L, null), row(6, 60L, null), row(7, 50L, null));
        List<Long> ids = List.of(90L, 80L, 70L, 60L, 50L);
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.read(7L, CURSOR, NOW)).thenReturn(found);
        when(contactMembers.getAll(ids)).thenReturn(List.of(new ContactMember(90L, " 원문 닉네임 ", true, false),
                new ContactMember(80L, "", true, false), new ContactMember(70L, "  ", true, false),
                new ContactMember(60L, "탈퇴한 회원", false, false), new ContactMember(50L, "탈퇴한 회원", false, true)));
        when(notes.unread(7L, NOW)).thenReturn(4L);

        List<Notification> expected = List.of(notification(found.get(0), 90L, " 원문 닉네임 "),
                notification(found.get(1), null, "탈퇴한 회원"), notification(found.get(2), 80L, ""),
                notification(found.get(3), 90L, " 원문 닉네임 "), notification(found.get(4), 70L, "  "),
                notification(found.get(5), null, "탈퇴한 회원"), notification(found.get(6), null, "탈퇴한 회원"));
        assertEquals(new NoteNotifications(expected, null, 4L), query.read(7L, CURSOR));

        var order = inOrder(members, clock, notes, contactMembers);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).read(7L, CURSOR, NOW);
        order.verify(contactMembers).getAll(ids);
        order.verify(notes).unread(7L, NOW);
        verifyNoMoreInteractions(members, clock, notes, contactMembers);
    }

    @Test
    void given__time_advances_during_caller_and_list__when__reading__then__capture_once_after_caller_and_reuse_that_instant_for_unread() {
        Instant[] time = {NOW.minusSeconds(1)};
        when(members.get(7L)).thenAnswer(invocation -> {
            time[0] = NOW;
            return CURRENT;
        });
        when(clock.instant()).thenAnswer(invocation -> time[0]);
        when(notes.read(7L, CURSOR, NOW)).thenAnswer(invocation -> {
            time[0] = NOW.plusSeconds(3600);
            return List.of();
        });
        when(notes.unread(7L, NOW)).thenReturn(0L);

        assertEquals(new NoteNotifications(List.of(), null, 0L), query.read(7L, CURSOR));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7L);
        order.verify(clock).instant();
        order.verify(notes).read(7L, CURSOR, NOW);
        order.verify(notes).unread(7L, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(contactMembers);
    }

    @ParameterizedTest
    @EnumSource(FailurePhase.class)
    void given__caller_clock_list_batch_or_unread_failure__when__reading__then__propagate_same_failure_and_stop_later_steps(FailurePhase phase) {
        RuntimeException failure = switch (phase) {
            case CALLER, BATCH -> new ContactMemberUnavailableException(new DataAccessResourceFailureException("private User SQL"));
            case CLOCK -> new IllegalStateException("private clock failure");
            case LIST -> new DataAccessResourceFailureException("private note SQL");
            case UNREAD -> new TransactionSystemException("private unread commit");
        };
        when(members.get(7L)).thenReturn(CURRENT);
        when(clock.instant()).thenReturn(NOW);
        when(notes.read(7L, CURSOR, NOW)).thenReturn(List.of(row(1, 8L, null)));
        when(contactMembers.getAll(List.of(8L))).thenReturn(List.of(new ContactMember(8L, "회원", true, false)));
        switch (phase) {
            case CALLER -> when(members.get(7L)).thenThrow(failure);
            case CLOCK -> when(clock.instant()).thenThrow(failure);
            case LIST -> when(notes.read(7L, CURSOR, NOW)).thenThrow(failure);
            case BATCH -> when(contactMembers.getAll(List.of(8L))).thenThrow(failure);
            case UNREAD -> when(notes.unread(7L, NOW)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> query.read(7L, CURSOR)));

        var order = inOrder(members, clock, notes, contactMembers);
        order.verify(members).get(7L);
        if (phase != FailurePhase.CALLER) order.verify(clock).instant();
        if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD)
            order.verify(notes).read(7L, CURSOR, NOW);
        if (phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD)
            order.verify(contactMembers).getAll(List.of(8L));
        if (phase == FailurePhase.UNREAD) order.verify(notes).unread(7L, NOW);
        verifyNoMoreInteractions(members, clock, notes, contactMembers);
    }

    private static Row row(int index, Long senderId, Instant readAt) {
        return new Row(new UUID(1L, index), senderId, NOW, readAt);
    }

    private static Notification notification(Row row, Long actorId, String nickname) {
        return new Notification(row.noteId(), "PRIVATE_NOTE", actorId, nickname, row.createdAt(),
                row.readAt() != null, "/notes/" + row.noteId());
    }

    private enum FailurePhase { CALLER, CLOCK, LIST, BATCH, UNREAD }
}
