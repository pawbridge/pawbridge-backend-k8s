package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class ListPrivateNotesTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val notes = mock(PrivateNoteListReader::class.java)
    private val contactMembers = mock(ContactMembersQuery::class.java)
    private val blocks = mock(NoteBlockReader::class.java)
    private val clock = mock(Clock::class.java)
    private val query = ListPrivateNotes(members, notes, contactMembers, blocks, clock)

    @ParameterizedTest
    @ValueSource(ints = [-1, 10001])
    fun given__invalid_page_box_and_caller__when__listing__then__reject_page_before_box_member_clock_or_sql(page: Int) {
        assertEquals(
            PrivateNoteListRejectedException.Reason.INVALID_PAGE,
            assertThrows(PrivateNoteListRejectedException::class.java) {
                query.read(0L, "invalid", false, page)
            }.reason(),
        )

        verifyNoInteractions(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["inbox", " INBOX"])
    fun given__raw_invalid_box_and_caller__when__listing__then__reject_box_without_case_or_whitespace_normalization_before_caller(
        box: String,
    ) {
        assertEquals(
            PrivateNoteListRejectedException.Reason.INVALID_BOX,
            assertThrows(PrivateNoteListRejectedException::class.java) { query.read(0L, box, false, 0) }.reason(),
        )

        verifyNoInteractions(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__valid_page_and_box_but_invalid_caller__when__listing__then__reject_login_before_dependencies(
        caller: Long,
    ) {
        assertEquals(
            PrivateNoteListRejectedException.Reason.LOGIN,
            assertThrows(PrivateNoteListRejectedException::class.java) {
                query.read(caller, "INBOX", false, 0)
            }.reason(),
        )

        verifyNoInteractions(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_caller__when__listing__then__reject_inactive_before_clock_count_list_batch_or_block(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            PrivateNoteListRejectedException.Reason.INACTIVE,
            assertThrows(PrivateNoteListRejectedException::class.java) {
                query.read(7L, "INBOX", false, 0)
            }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @MethodSource("emptyPages")
    fun given__empty_page_with_zero_or_large_count__when__listing__then__still_list_and_preserve_raw_count_requested_page_and_size(
        count: Long,
        page: Int,
        totalPages: Int,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7L, "SENT", true, NOW)).thenReturn(count)
        whenever(notes.read(7L, "SENT", true, page, NOW)).thenReturn(listOf())

        assertEquals(PrivateNotePage(listOf(), count, totalPages, page, 10), query.read(7L, "SENT", true, page))

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).count(7L, "SENT", true, NOW)
        order.verify(notes).read(7L, "SENT", true, page, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["INBOX", "SENT"])
    fun given__page_with_only_null_counterparts__when__listing__then__keep_notes_and_skip_batch_and_block(box: String) {
        val sender: Long? = if (box == "INBOX") null else 7L
        val recipient: Long? = if (box == "INBOX") 7L else null
        val note = StoredNote(
            UUID(23L, 1L), sender, recipient, " 原文\n본문 ", null, null,
            NOW.minusSeconds(1), box, NOW, true,
        )
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7L, box, false, NOW)).thenReturn(1L)
        whenever(notes.read(7L, box, false, 0, NOW)).thenReturn(listOf(note))

        assertEquals(
            PrivateNotePage(listOf(view(note, null, "탈퇴한 회원", false, null)), 1L, 1, 0, 10),
            query.read(7L, box, false, 0),
        )

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).count(7L, box, false, NOW)
        order.verify(notes).read(7L, box, false, 0, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(contactMembers, blocks)
    }

    @Test
    fun given__mixed_duplicate_null_missing_pending_and_raw_names_after_zero_count__when__listing__then__batch_once_and_block_each_active_inbox_in_content_order_without_pair_cache() {
        val first = StoredNote(
            UUID(23L, 1L), 8L, 7L, " 원문\n본문 ", "POST", Long.MAX_VALUE,
            NOW.minusSeconds(1), "INBOX", null, true,
        )
        val found = listOf(
            first, inbox(2, null), inbox(3, 9L), inbox(4, 8L),
            inbox(5, 10L), inbox(6, 11L), inbox(7, 12L),
        )
        val ids = listOf(8L, 9L, 10L, 11L, 12L)
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7L, "INBOX", false, NOW)).thenReturn(0L)
        whenever(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(found)
        whenever(contactMembers.getAll(ids)).thenReturn(
            listOf(
                ContactMember(8L, " 原文 이름 ", true, false),
                ContactMember(9L, "탈퇴한 회원", false, false),
                ContactMember(10L, "탈퇴한 회원", false, true),
                ContactMember(11L, "", true, false),
                ContactMember(12L, "  ", true, false),
            ),
        )
        whenever(blocks.blocked(7L, 8L)).thenReturn(false, true)
        whenever(blocks.blocked(7L, 12L)).thenReturn(true)

        val expected = listOf(
            view(first, 8L, " 原文 이름 ", true, "/community/" + Long.MAX_VALUE),
            view(found[1], null, "탈퇴한 회원", false, null),
            view(found[2], null, "탈퇴한 회원", false, null),
            view(found[3], 8L, " 原文 이름 ", false, null),
            view(found[4], null, "탈퇴한 회원", false, null),
            view(found[5], 11L, "", true, null),
            view(found[6], 12L, "  ", false, null),
        )
        assertEquals(PrivateNotePage(expected, 0L, 0, 0, 10), query.read(7L, "INBOX", false, 0))

        val order = inOrder(members, clock, notes, contactMembers, blocks)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).count(7L, "INBOX", false, NOW)
        order.verify(notes).read(7L, "INBOX", false, 0, NOW)
        order.verify(contactMembers).getAll(ids)
        order.verify(blocks, times(2)).blocked(7L, 8L)
        order.verify(blocks).blocked(7L, 11L)
        order.verify(blocks).blocked(7L, 12L)
        verifyNoMoreInteractions(members, clock, notes, contactMembers, blocks)
    }

    @Test
    fun given__sent_page_with_active_recipient__when__listing__then__display_batch_recipient_report_link_and_page_without_block_lookup() {
        val note = StoredNote(
            UUID(23L, 8L), 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", 13L,
            NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false,
        )
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7L, "SENT", false, NOW)).thenReturn(11L)
        whenever(notes.read(7L, "SENT", false, 1, NOW)).thenReturn(listOf(note))
        whenever(contactMembers.getAll(listOf(Long.MAX_VALUE)))
            .thenReturn(listOf(ContactMember(Long.MAX_VALUE, "수신자", true, false)))

        assertEquals(
            PrivateNotePage(listOf(view(note, Long.MAX_VALUE, "수신자", false, "/reports/13")), 11L, 2, 1, 10),
            query.read(7L, "SENT", false, 1),
        )

        val order = inOrder(members, clock, notes, contactMembers)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).count(7L, "SENT", false, NOW)
        order.verify(notes).read(7L, "SENT", false, 1, NOW)
        order.verify(contactMembers).getAll(listOf(Long.MAX_VALUE))
        verifyNoMoreInteractions(members, clock, notes, contactMembers)
        verifyNoInteractions(blocks)
    }

    @Test
    fun given__time_advances_during_caller_and_count__when__listing__then__capture_once_after_caller_and_reuse_that_instant_for_list() {
        var time = NOW.minusSeconds(1)
        whenever(members.get(7L)).thenAnswer {
            time = NOW
            CURRENT
        }
        whenever(clock.instant()).thenAnswer { time }
        whenever(notes.count(7L, "INBOX", false, NOW)).thenAnswer {
            time = NOW.plusSeconds(3600)
            0L
        }
        whenever(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(listOf())

        assertEquals(PrivateNotePage(listOf(), 0L, 0, 0, 10), query.read(7L, "INBOX", false, 0))

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7L)
        order.verify(clock).instant()
        order.verify(notes).count(7L, "INBOX", false, NOW)
        order.verify(notes).read(7L, "INBOX", false, 0, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(contactMembers, blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__caller_clock_count_list_batch_or_first_or_late_block_failure__when__listing__then__propagate_same_failure_stop_later_steps_and_return_no_partial_page(
        phase: FailurePhase,
    ) {
        val found = listOf(inbox(1, 8L), inbox(2, 9L), inbox(3, 10L))
        val ids = listOf(8L, 9L, 10L)
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private caller SQL"))
            FailurePhase.CLOCK -> IllegalStateException("private clock")
            FailurePhase.COUNT -> DataAccessResourceFailureException("private count SQL")
            FailurePhase.LIST -> CannotCreateTransactionException("private list begin")
            FailurePhase.BATCH ->
                ContactMemberUnavailableException(TransactionSystemException("private batch commit"))
            FailurePhase.FIRST_BLOCK -> TransactionTimedOutException("private block timeout")
            FailurePhase.LATE_BLOCK -> TransactionSystemException("private second block commit")
        }
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7L, "INBOX", false, NOW)).thenReturn(3L)
        whenever(notes.read(7L, "INBOX", false, 0, NOW)).thenReturn(found)
        whenever(contactMembers.getAll(ids)).thenReturn(ids.map { id -> ContactMember(id, "회원", true, false) })
        when (phase) {
            FailurePhase.CALLER -> whenever(members.get(7L)).thenThrow(failure)
            FailurePhase.CLOCK -> whenever(clock.instant()).thenThrow(failure)
            FailurePhase.COUNT -> whenever(notes.count(7L, "INBOX", false, NOW)).thenThrow(failure)
            FailurePhase.LIST -> whenever(notes.read(7L, "INBOX", false, 0, NOW)).thenThrow(failure)
            FailurePhase.BATCH -> whenever(contactMembers.getAll(ids)).thenThrow(failure)
            FailurePhase.FIRST_BLOCK -> whenever(blocks.blocked(7L, 8L)).thenThrow(failure)
            FailurePhase.LATE_BLOCK -> whenever(blocks.blocked(7L, 9L)).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { query.read(7L, "INBOX", false, 0) })

        val order = inOrder(members, clock, notes, contactMembers, blocks)
        order.verify(members).get(7L)
        if (phase != FailurePhase.CALLER) {
            order.verify(clock).instant()
        }
        if (phase != FailurePhase.CALLER && phase != FailurePhase.CLOCK) {
            order.verify(notes).count(7L, "INBOX", false, NOW)
        }
        if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH ||
            phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK
        ) {
            order.verify(notes).read(7L, "INBOX", false, 0, NOW)
        }
        if (phase == FailurePhase.BATCH || phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK) {
            order.verify(contactMembers).getAll(ids)
        }
        if (phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK) {
            order.verify(blocks).blocked(7L, 8L)
        }
        if (phase == FailurePhase.LATE_BLOCK) {
            order.verify(blocks).blocked(7L, 9L)
        }
        verifyNoMoreInteractions(members, clock, notes, contactMembers, blocks)
    }

    enum class FailurePhase { CALLER, CLOCK, COUNT, LIST, BATCH, FIRST_BLOCK, LATE_BLOCK }

    companion object {
        private val NOW = Instant.parse("2031-01-01T14:59:59.123456Z")
        private val CURRENT = ContactMember(7L, "현재 회원", true, false)

        @JvmStatic
        fun emptyPages(): Stream<Arguments> =
            Stream.of(Arguments.of(0L, 0, 0), Arguments.of(5_000_000_000L, 10000, 500_000_000))

        private fun inbox(index: Int, senderId: Long?): StoredNote =
            StoredNote(
                UUID(23L, index.toLong()),
                senderId,
                7L,
                " 본문\n$index ",
                null,
                null,
                NOW.minusSeconds(index.toLong()),
                "INBOX",
                if (index % 2 == 0) NOW else null,
                index % 2 == 0,
            )

        private fun view(
            note: StoredNote,
            counterpartId: Long?,
            nickname: String?,
            canReply: Boolean,
            href: String?,
        ): PrivateNoteView =
            PrivateNoteView(
                note.noteId,
                note.body,
                note.direction,
                note.createdAt,
                note.readAt,
                note.favorite,
                counterpartId,
                nickname,
                canReply,
                href,
            )
    }
}
