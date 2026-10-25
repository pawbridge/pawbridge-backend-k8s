package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
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
import org.springframework.transaction.TransactionSystemException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ReadNoteNotificationsTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val notes = mock(NoteNotificationReader::class.java)
    private val contactMembers = mock(ContactMembersQuery::class.java)
    private val clock = mock(Clock::class.java)
    private val query = ReadNoteNotifications(members, notes, contactMembers, clock)

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__invalid_current_id__when__reading__then__reject_login_before_member_clock_or_sql(currentUserId: Long) {
        assertEquals(
            NoteNotificationsRejectedException.Reason.LOGIN,
            assertThrows(NoteNotificationsRejectedException::class.java) {
                query.read(currentUserId, CURSOR)
            }.reason(),
        )

        verifyNoInteractions(members, clock, notes, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_caller__when__reading__then__reject_inactive_before_clock_list_batch_and_unread(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            NoteNotificationsRejectedException.Reason.INACTIVE,
            assertThrows(NoteNotificationsRejectedException::class.java) { query.read(7L, CURSOR) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock, notes, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__empty_or_only_null_senders__when__reading__then__skip_batch_but_return_last_unread_raw_count(
        nullSender: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        val row = row(1, null, null)
        whenever(notes.read(7L, CURSOR, NOW)).thenReturn(if (nullSender) listOf(row) else listOf())
        whenever(notes.unread(7L, NOW)).thenReturn(Long.MAX_VALUE)

        val content: List<Notification> =
            if (nullSender) {
                listOf(Notification(row.noteId, "PRIVATE_NOTE", null, "탈퇴한 회원", NOW, false, "/notes/" + row.noteId))
            } else {
                listOf()
            }
        assertEquals(NoteNotifications(content, null, Long.MAX_VALUE), query.read(7L, CURSOR))

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).read(7L, CURSOR, NOW)
        order.verify(notes).unread(7L, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(contactMembers)
    }

    @ParameterizedTest
    @ValueSource(ints = [20, 21])
    fun given__twenty_or_twenty_one_rows__when__reading__then__batch_only_displayed_twenty_and_use_last_displayed_note_as_next_cursor(
        size: Int,
    ) {
        val found = (0 until size).map { index ->
            row(index, 100L + index, if (index % 2 == 0) null else NOW.minusSeconds(1))
        }
        val ids = (0 until 20).map { index -> 100L + index }
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.read(7L, null, NOW)).thenReturn(found)
        whenever(contactMembers.getAll(ids)).thenReturn(ids.map { id -> ContactMember(id, "회원$id", true, false) })
        whenever(notes.unread(7L, NOW)).thenReturn(31L)

        val expected = found.subList(0, 20).map { row ->
            Notification(
                row.noteId,
                "PRIVATE_NOTE",
                row.senderId,
                "회원" + row.senderId,
                row.createdAt,
                row.readAt != null,
                "/notes/" + row.noteId,
            )
        }
        assertEquals(
            NoteNotifications(expected, if (size == 21) found[19].noteId else null, 31L),
            query.read(7L, null),
        )

        val order = inOrder(members, clock, notes, contactMembers)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).read(7L, null, NOW)
        order.verify(contactMembers).getAll(ids)
        order.verify(notes).unread(7L, NOW)
        verifyNoMoreInteractions(members, clock, notes, contactMembers)
    }

    @Test
    fun given__duplicate_null_active_missing_and_pending_senders__when__reading__then__distinct_once_preserve_order_raw_names_and_nullable_actor_ids() {
        val found = listOf(
            row(1, 90L, null), row(2, null, NOW), row(3, 80L, null),
            row(4, 90L, NOW), row(5, 70L, null), row(6, 60L, null), row(7, 50L, null),
        )
        val ids = listOf(90L, 80L, 70L, 60L, 50L)
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.read(7L, CURSOR, NOW)).thenReturn(found)
        whenever(contactMembers.getAll(ids)).thenReturn(
            listOf(
                ContactMember(90L, " 원문 닉네임 ", true, false),
                ContactMember(80L, "", true, false),
                ContactMember(70L, "  ", true, false),
                ContactMember(60L, "탈퇴한 회원", false, false),
                ContactMember(50L, "탈퇴한 회원", false, true),
            ),
        )
        whenever(notes.unread(7L, NOW)).thenReturn(4L)

        val expected = listOf(
            notification(found[0], 90L, " 원문 닉네임 "),
            notification(found[1], null, "탈퇴한 회원"),
            notification(found[2], 80L, ""),
            notification(found[3], 90L, " 원문 닉네임 "),
            notification(found[4], 70L, "  "),
            notification(found[5], null, "탈퇴한 회원"),
            notification(found[6], null, "탈퇴한 회원"),
        )
        assertEquals(NoteNotifications(expected, null, 4L), query.read(7L, CURSOR))

        val order = inOrder(members, clock, notes, contactMembers)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).read(7L, CURSOR, NOW)
        order.verify(contactMembers).getAll(ids)
        order.verify(notes).unread(7L, NOW)
        verifyNoMoreInteractions(members, clock, notes, contactMembers)
    }

    @Test
    fun given__time_advances_during_caller_and_list__when__reading__then__capture_once_after_caller_and_reuse_that_instant_for_unread() {
        var time = NOW.minusSeconds(1)
        whenever(members.get(7L)).thenAnswer {
            time = NOW
            CURRENT
        }
        whenever(clock.instant()).thenAnswer { time }
        whenever(notes.read(7L, CURSOR, NOW)).thenAnswer {
            time = NOW.plusSeconds(3600)
            listOf<Row>()
        }
        whenever(notes.unread(7L, NOW)).thenReturn(0L)

        assertEquals(NoteNotifications(listOf(), null, 0L), query.read(7L, CURSOR))

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).read(7L, CURSOR, NOW)
        order.verify(notes).unread(7L, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(contactMembers)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__caller_clock_list_batch_or_unread_failure__when__reading__then__propagate_same_failure_and_stop_later_steps(
        phase: FailurePhase,
    ) {
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER, FailurePhase.BATCH ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.CLOCK -> IllegalStateException("private clock failure")
            FailurePhase.LIST -> DataAccessResourceFailureException("private note SQL")
            FailurePhase.UNREAD -> TransactionSystemException("private unread commit")
        }
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.read(7L, CURSOR, NOW)).thenReturn(listOf(row(1, 8L, null)))
        whenever(contactMembers.getAll(listOf(8L))).thenReturn(listOf(ContactMember(8L, "회원", true, false)))
        when (phase) {
            FailurePhase.CALLER -> whenever(members.get(7L)).thenThrow(failure)
            FailurePhase.CLOCK -> whenever(clock.instant()).thenThrow(failure)
            FailurePhase.LIST -> whenever(notes.read(7L, CURSOR, NOW)).thenThrow(failure)
            FailurePhase.BATCH -> whenever(contactMembers.getAll(listOf(8L))).thenThrow(failure)
            FailurePhase.UNREAD -> whenever(notes.unread(7L, NOW)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { query.read(7L, CURSOR) })

        val order = inOrder(members, clock, notes, contactMembers)
        order.verify(members).get(7L)
        if (phase != FailurePhase.CALLER) {
            order.verify(clock).instant()
        }
        if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD) {
            order.verify(notes).read(7L, CURSOR, NOW)
        }
        if (phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD) {
            order.verify(contactMembers).getAll(listOf(8L))
        }
        if (phase == FailurePhase.UNREAD) {
            order.verify(notes).unread(7L, NOW)
        }
        verifyNoMoreInteractions(members, clock, notes, contactMembers)
    }

    enum class FailurePhase { CALLER, CLOCK, LIST, BATCH, UNREAD }

    private companion object {
        val NOW: Instant = Instant.parse("2031-01-01T14:59:59.123456Z")
        val CURSOR = UUID(1L, 9L)
        val CURRENT = ContactMember(7L, "현재 회원", true, false)

        fun row(index: Int, senderId: Long?, readAt: Instant?): Row =
            Row(UUID(1L, index.toLong()), senderId, NOW, readAt)

        fun notification(row: Row, actorId: Long?, nickname: String?): Notification =
            Notification(
                row.noteId,
                "PRIVATE_NOTE",
                actorId,
                nickname,
                row.createdAt,
                row.readAt != null,
                "/notes/" + row.noteId,
            )
    }
}
