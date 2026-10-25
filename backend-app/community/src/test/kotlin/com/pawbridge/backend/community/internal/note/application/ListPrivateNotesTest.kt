package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class ListPrivateNotesTest {
    private val members = mockk<ContactMemberQuery>()
    private val notes = mockk<PrivateNoteListReader>()
    private val contactMembers = mockk<ContactMembersQuery>()
    private val blocks = mockk<NoteBlockReader>()
    private val clock = mockk<Clock>()
    private val query = ListPrivateNotes(members, notes, contactMembers, blocks, clock)

    @ParameterizedTest
    @ValueSource(ints = [-1, 10001])
    fun `잘못된 쪽 번호와 상자와 호출자가 주어졌을 때__목록을 읽으면__상자와 회원 조회와 시각 읽기와 SQL 전에 쪽 번호를 거절한다`(page: Int) {
        // when
        val failure = assertThrows(PrivateNoteListRejectedException::class.java) {
            query.read(0L, "invalid", false, page)
        }

        // then
        assertEquals(PrivateNoteListRejectedException.Reason.INVALID_PAGE, failure.reason())
        confirmVerified(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["inbox", " INBOX"])
    fun `원문 그대로 잘못된 상자와 호출자가 주어졌을 때__목록을 읽으면__대소문자나 공백을 고치지 않고 호출자 확인 전에 상자를 거절한다`(
        box: String,
    ) {
        // when
        val failure = assertThrows(PrivateNoteListRejectedException::class.java) { query.read(0L, box, false, 0) }

        // then
        assertEquals(PrivateNoteListRejectedException.Reason.INVALID_BOX, failure.reason())
        confirmVerified(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `유효한 쪽과 상자지만 잘못된 호출자가 주어졌을 때__목록을 읽으면__의존성 호출 전에 로그인 거절한다`(
        caller: Long,
    ) {
        // when
        val failure = assertThrows(PrivateNoteListRejectedException::class.java) {
            query.read(caller, "INBOX", false, 0)
        }

        // then
        assertEquals(PrivateNoteListRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `호출자가 없거나 탈퇴 대기일 때__목록을 읽으면__시각 읽기와 개수와 목록과 일괄 조회와 차단 확인 전에 비활성으로 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(PrivateNoteListRejectedException::class.java) {
            query.read(7L, "INBOX", false, 0)
        }

        // then
        assertEquals(PrivateNoteListRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, clock, notes, contactMembers, blocks)
    }

    @ParameterizedTest
    @MethodSource("emptyPages")
    fun `개수가 0이거나 큰 빈 쪽이 주어졌을 때__목록을 읽으면__그래도 목록을 읽고 원본 개수와 요청한 쪽과 크기를 보존한다`(
        count: Long,
        page: Int,
        totalPages: Int,
    ) {
        // given
        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns count
        every { notes.read(any(), any(), any(), any(), any()) } returns listOf()

        // when
        val result = query.read(7L, "SENT", true, page)

        // then
        assertEquals(PrivateNotePage(listOf(), count, totalPages, page, 10), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.count(7L, "SENT", true, NOW)
            notes.read(7L, "SENT", true, page, NOW)
        }
        confirmVerified(contactMembers, blocks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["INBOX", "SENT"])
    fun `상대가 모두 null인 쪽이 주어졌을 때__목록을 읽으면__쪽지는 유지하고 일괄 조회와 차단 확인만 건너뛴다`(box: String) {
        // given
        val sender: Long? = if (box == "INBOX") null else 7L
        val recipient: Long? = if (box == "INBOX") 7L else null
        val note = StoredNote(
            UUID(23L, 1L), sender, recipient, " 原文\n본문 ", null, null,
            NOW.minusSeconds(1), box, NOW, true,
        )

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns 1L
        every { notes.read(any(), any(), any(), any(), any()) } returns listOf(note)

        // when
        val result = query.read(7L, box, false, 0)

        // then
        assertEquals(PrivateNotePage(listOf(view(note, null, "탈퇴한 회원", false, null)), 1L, 1, 0, 10), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.count(7L, box, false, NOW)
            notes.read(7L, box, false, 0, NOW)
        }
        confirmVerified(contactMembers, blocks)
    }

    @Test
    fun `개수 0 뒤에 중복과 없음과 탈퇴 대기 쪽지가 섞여 있을 때__목록을 읽으면__일괄 조회는 한 번 하고 쪽지마다 순서대로 캐시 없이 차단을 확인한다`() {
        // given
        val first = StoredNote(
            UUID(23L, 1L), 8L, 7L, " 원문\n본문 ", "POST", Long.MAX_VALUE,
            NOW.minusSeconds(1), "INBOX", null, true,
        )
        val found = listOf(
            first, inbox(2, null), inbox(3, 9L), inbox(4, 8L),
            inbox(5, 10L), inbox(6, 11L), inbox(7, 12L),
        )
        val ids = listOf(8L, 9L, 10L, 11L, 12L)

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns 0L
        every { notes.read(any(), any(), any(), any(), any()) } returns found
        every { contactMembers.getAll(any()) } returns listOf(
            ContactMember(8L, " 原文 이름 ", true, false),
            ContactMember(9L, "탈퇴한 회원", false, false),
            ContactMember(10L, "탈퇴한 회원", false, true),
            ContactMember(11L, "", true, false),
            ContactMember(12L, "  ", true, false),
        )
        every { blocks.blocked(7L, 8L) } returnsMany listOf(false, true)
        every { blocks.blocked(7L, 11L) } returns false
        every { blocks.blocked(7L, 12L) } returns true

        // when
        val result = query.read(7L, "INBOX", false, 0)

        // then
        val expected = listOf(
            view(first, 8L, " 原文 이름 ", true, "/community/" + Long.MAX_VALUE),
            view(found[1], null, "탈퇴한 회원", false, null),
            view(found[2], null, "탈퇴한 회원", false, null),
            view(found[3], 8L, " 原文 이름 ", false, null),
            view(found[4], null, "탈퇴한 회원", false, null),
            view(found[5], 11L, "", true, null),
            view(found[6], 12L, "  ", false, null),
        )
        assertEquals(PrivateNotePage(expected, 0L, 0, 0, 10), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.count(7L, "INBOX", false, NOW)
            notes.read(7L, "INBOX", false, 0, NOW)
            contactMembers.getAll(ids)
            blocks.blocked(7L, 8L)
            blocks.blocked(7L, 8L)
            blocks.blocked(7L, 11L)
            blocks.blocked(7L, 12L)
        }
    }

    @Test
    fun `활성 수신자가 있는 보낸 쪽이 주어졌을 때__목록을 읽으면__차단 조회 없이 일괄 조회한 수신자와 신고 링크와 쪽을 보여준다`() {
        // given
        val note = StoredNote(
            UUID(23L, 8L), 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", 13L,
            NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false,
        )

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns 11L
        every { notes.read(any(), any(), any(), any(), any()) } returns listOf(note)
        every { contactMembers.getAll(any()) } returns listOf(ContactMember(Long.MAX_VALUE, "수신자", true, false))

        // when
        val result = query.read(7L, "SENT", false, 1)

        // then
        assertEquals(
            PrivateNotePage(listOf(view(note, Long.MAX_VALUE, "수신자", false, "/reports/13")), 11L, 2, 1, 10),
            result,
        )
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.count(7L, "SENT", false, NOW)
            notes.read(7L, "SENT", false, 1, NOW)
            contactMembers.getAll(listOf(Long.MAX_VALUE))
        }
        confirmVerified(blocks)
    }

    @Test
    fun `호출자 조회와 개수 조회 중 시간이 흘렀을 때__목록을 읽으면__호출자 조회 뒤 한 번만 시각을 잡고 목록에 그 시각을 재사용한다`() {
        // given
        var time = NOW.minusSeconds(1)

        every { members.get(any()) } answers {
            time = NOW
            CURRENT
        }
        every { clock.instant() } answers { time }
        every { notes.count(any(), any(), any(), any()) } answers {
            time = NOW.plusSeconds(3600)
            0L
        }
        every { notes.read(any(), any(), any(), any(), any()) } returns listOf()

        // when
        val result = query.read(7L, "INBOX", false, 0)

        // then
        assertEquals(PrivateNotePage(listOf(), 0L, 0, 0, 10), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.count(7L, "INBOX", false, NOW)
            notes.read(7L, "INBOX", false, 0, NOW)
        }
        confirmVerified(contactMembers, blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `조회나 차단 확인이 실패할 때__목록을 읽으면__같은 실패를 전파하고 이후 단계를 멈추며 부분 쪽을 돌려주지 않는다`(
        phase: FailurePhase,
    ) {
        // given
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

        if (phase == FailurePhase.CALLER) {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns CURRENT
        }
        if (phase == FailurePhase.CLOCK) {
            every { clock.instant() } throws failure
        } else {
            every { clock.instant() } returns NOW
        }
        if (phase == FailurePhase.COUNT) {
            every { notes.count(any(), any(), any(), any()) } throws failure
        } else {
            every { notes.count(any(), any(), any(), any()) } returns 3L
        }
        if (phase == FailurePhase.LIST) {
            every { notes.read(any(), any(), any(), any(), any()) } throws failure
        } else {
            every { notes.read(any(), any(), any(), any(), any()) } returns found
        }
        if (phase == FailurePhase.BATCH) {
            every { contactMembers.getAll(any()) } throws failure
        } else {
            every { contactMembers.getAll(any()) } returns ids.map { id -> ContactMember(id, "회원", true, false) }
        }
        when (phase) {
            FailurePhase.FIRST_BLOCK -> every { blocks.blocked(any(), any()) } throws failure
            FailurePhase.LATE_BLOCK -> {
                every { blocks.blocked(7L, 8L) } returns false
                every { blocks.blocked(7L, 9L) } throws failure
            }
            else -> every { blocks.blocked(any(), any()) } returns false
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, "INBOX", false, 0) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.CALLER) {
                clock.instant()
            }
            if (phase != FailurePhase.CALLER && phase != FailurePhase.CLOCK) {
                notes.count(7L, "INBOX", false, NOW)
            }
            if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH ||
                phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK
            ) {
                notes.read(7L, "INBOX", false, 0, NOW)
            }
            if (phase == FailurePhase.BATCH || phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK) {
                contactMembers.getAll(ids)
            }
            if (phase == FailurePhase.FIRST_BLOCK || phase == FailurePhase.LATE_BLOCK) {
                blocks.blocked(7L, 8L)
            }
            if (phase == FailurePhase.LATE_BLOCK) {
                blocks.blocked(7L, 9L)
            }
        }
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
