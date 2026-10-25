package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
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
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ReadPrivateNoteTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val notes = mock(PrivateNoteReader::class.java)
    private val blocks = mock(NoteBlockReader::class.java)
    private val clock = mock(Clock::class.java)
    private val query = ReadPrivateNote(members, notes, blocks, clock)

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__invalid_current_id__when__reading__then__reject_login_before_member_clock_or_sql(currentUserId: Long) {
        assertEquals(
            PrivateNoteRejectedException.Reason.LOGIN,
            assertThrows(PrivateNoteRejectedException::class.java) { query.read(currentUserId, NOTE_ID) }.reason(),
        )

        verifyNoInteractions(members, clock, notes, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_caller__when__reading__then__reject_inactive_before_clock_find_counterpart_and_block(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            PrivateNoteRejectedException.Reason.INACTIVE,
            assertThrows(PrivateNoteRejectedException::class.java) { query.read(7L, NOTE_ID) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock, notes, blocks)
    }

    @Test
    fun given__no_visible_note__when__reading__then__reject_not_found_after_caller_and_find_without_counterpart_or_block() {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.empty())

        assertEquals(
            PrivateNoteRejectedException.Reason.NOT_FOUND,
            assertThrows(PrivateNoteRejectedException::class.java) { query.read(7L, NOTE_ID) }.reason(),
        )

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).find(7L, NOTE_ID, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__active_inbox_counterpart_and_block_status__when__reading__then__preserve_ten_fields_and_return_can_reply_without_rejecting_blocked_note(
        blocked: Boolean,
    ) {
        val note = StoredNote(
            NOTE_ID, 8L, 7L, " 원문\n쪽지 본문 ", "POST", Long.MAX_VALUE,
            NOW.minusSeconds(5), "INBOX", null, true,
        )
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note))
        whenever(members.get(8L)).thenReturn(COUNTERPART)
        whenever(blocks.blocked(7L, 8L)).thenReturn(blocked)

        assertEquals(
            PrivateNoteView(
                NOTE_ID, " 원문\n쪽지 본문 ", "INBOX", NOW.minusSeconds(5),
                null, true, 8L, " 원문 닉네임 ", !blocked, "/community/" + Long.MAX_VALUE,
            ),
            query.read(7L, NOTE_ID),
        )

        val order = inOrder(members, clock, notes, blocks)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).find(7L, NOTE_ID, NOW)
        order.verify(members).get(8L)
        order.verify(blocks).blocked(7L, 8L)
        verifyNoMoreInteractions(members, clock, notes, blocks)
    }

    @Test
    fun given__sent_note_with_active_recipient__when__reading__then__display_recipient_and_report_link_without_block_lookup() {
        val note = StoredNote(
            NOTE_ID, 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", Long.MAX_VALUE,
            NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false,
        )
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note))
        whenever(members.get(Long.MAX_VALUE)).thenReturn(ContactMember(Long.MAX_VALUE, "수신자", true, false))

        assertEquals(
            PrivateNoteView(
                NOTE_ID, "보낸 원문", "SENT", NOW.minusSeconds(5), NOW.minusSeconds(1),
                false, Long.MAX_VALUE, "수신자", false, "/reports/" + Long.MAX_VALUE,
            ),
            query.read(7L, NOTE_ID),
        )

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).find(7L, NOTE_ID, NOW)
        order.verify(members).get(Long.MAX_VALUE)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(blocks)
    }

    @ParameterizedTest
    @EnumSource(CounterpartState::class)
    fun given__null_missing_pending_or_raw_empty_counterpart__when__reading__then__apply_withdrawn_policy_and_only_block_check_active_inbox(
        state: CounterpartState,
    ) {
        val senderId: Long? = if (state == CounterpartState.NULL_ID) null else 8L
        val note = StoredNote(NOTE_ID, senderId, 7L, "본문", null, null, NOW, "INBOX", null, false)
        val active = state == CounterpartState.EMPTY_NAME || state == CounterpartState.SPACE_NAME
        val nickname = if (state == CounterpartState.EMPTY_NAME) "" else "  "
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(note))
        if (senderId != null) {
            whenever(members.get(senderId)).thenReturn(
                ContactMember(senderId, nickname, active, state == CounterpartState.PENDING),
            )
        }

        assertEquals(
            PrivateNoteView(
                NOTE_ID, "본문", "INBOX", NOW, null, false,
                if (active) 8L else null, if (active) nickname else "탈퇴한 회원", active, null,
            ),
            query.read(7L, NOTE_ID),
        )

        val order = inOrder(members, clock, notes, blocks)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).find(7L, NOTE_ID, NOW)
        if (senderId != null) {
            order.verify(members).get(senderId)
        }
        if (active) {
            order.verify(blocks).blocked(7L, 8L)
        }
        verifyNoMoreInteractions(members, clock, notes, blocks)
    }

    @Test
    fun given__time_advances_during_caller_and_counterpart__when__reading__then__capture_once_after_caller_and_before_find() {
        var time = NOW.minusSeconds(1)
        whenever(members.get(7L)).thenAnswer {
            time = NOW
            CURRENT
        }
        whenever(clock.instant()).thenAnswer { time }
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(inbox()))
        whenever(members.get(8L)).thenAnswer {
            time = NOW.plusSeconds(3600)
            COUNTERPART
        }

        assertEquals(NOW, query.read(7L, NOTE_ID).createdAt)

        val order = inOrder(members, clock, notes, blocks)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).find(7L, NOTE_ID, NOW)
        order.verify(members).get(8L)
        order.verify(blocks).blocked(7L, 8L)
        verifyNoMoreInteractions(members, clock, notes, blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__caller_clock_find_counterpart_or_block_failure__when__reading__then__propagate_same_failure_and_stop_later_steps(
        phase: FailurePhase,
    ) {
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.COUNTERPART ->
                ContactMemberUnavailableException(TransactionSystemException("private User commit"))
            FailurePhase.CLOCK -> IllegalStateException("private clock")
            FailurePhase.FIND -> DataAccessResourceFailureException("private note SQL")
            FailurePhase.BLOCK -> TransactionTimedOutException("private block timeout")
        }
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7L, NOTE_ID, NOW)).thenReturn(Optional.of(inbox()))
        whenever(members.get(8L)).thenReturn(COUNTERPART)
        when (phase) {
            FailurePhase.CALLER -> whenever(members.get(7L)).thenThrow(failure)
            FailurePhase.CLOCK -> whenever(clock.instant()).thenThrow(failure)
            FailurePhase.FIND -> whenever(notes.find(7L, NOTE_ID, NOW)).thenThrow(failure)
            FailurePhase.COUNTERPART -> whenever(members.get(8L)).thenThrow(failure)
            FailurePhase.BLOCK -> whenever(blocks.blocked(7L, 8L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { query.read(7L, NOTE_ID) })

        val order = inOrder(members, clock, notes, blocks)
        order.verify(members).get(7L)
        if (phase != FailurePhase.CALLER) {
            order.verify(clock).instant()
        }
        if (phase == FailurePhase.FIND || phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK) {
            order.verify(notes).find(7L, NOTE_ID, NOW)
        }
        if (phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK) {
            order.verify(members).get(8L)
        }
        if (phase == FailurePhase.BLOCK) {
            order.verify(blocks).blocked(7L, 8L)
        }
        verifyNoMoreInteractions(members, clock, notes, blocks)
    }

    enum class CounterpartState { NULL_ID, MISSING, PENDING, EMPTY_NAME, SPACE_NAME }

    enum class FailurePhase { CALLER, CLOCK, FIND, COUNTERPART, BLOCK }

    private companion object {
        val NOW: Instant = Instant.parse("2031-01-01T14:59:59.123456Z")
        val NOTE_ID = UUID(1L, 9L)
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
        val COUNTERPART = ContactMember(8L, " 원문 닉네임 ", true, false)

        fun inbox(): StoredNote = StoredNote(NOTE_ID, 8L, 7L, "본문", null, null, NOW, "INBOX", null, false)
    }
}
