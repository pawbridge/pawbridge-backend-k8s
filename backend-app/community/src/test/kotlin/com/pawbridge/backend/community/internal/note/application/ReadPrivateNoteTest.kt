package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ReadPrivateNoteTest {
    private val members = mockk<ContactMemberQuery>()
    private val notes = mockk<PrivateNoteReader>()
    private val blocks = mockk<NoteBlockReader>()
    private val clock = mockk<Clock>()
    private val query = ReadPrivateNote(members, notes, blocks, clock)

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `0 이하의 현재 회원 ID가 주어졌을 때__읽으면__회원 조회와 시각 읽기와 SQL 전에 로그인 거절한다`(currentUserId: Long) {
        // when
        val failure = assertThrows(PrivateNoteRejectedException::class.java) { query.read(currentUserId, NOTE_ID) }

        // then
        assertEquals(PrivateNoteRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, clock, notes, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `호출자가 없거나 탈퇴 대기일 때__읽으면__시각 읽기와 쪽지 조회와 상대 조회와 차단 확인 전에 비활성으로 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(PrivateNoteRejectedException::class.java) { query.read(7L, NOTE_ID) }

        // then
        assertEquals(PrivateNoteRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, clock, notes, blocks)
    }

    @Test
    fun `보이는 쪽지가 없을 때__읽으면__호출자와 쪽지 조회 뒤 상대 조회나 차단 확인 없이 찾을 수 없음으로 거절한다`() {
        // given
        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.empty()

        // when
        val failure = assertThrows(PrivateNoteRejectedException::class.java) { query.read(7L, NOTE_ID) }

        // then
        assertEquals(PrivateNoteRejectedException.Reason.NOT_FOUND, failure.reason())
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.find(7L, NOTE_ID, NOW)
        }
        confirmVerified(blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `활성 상대가 보낸 받은 쪽지와 차단 상태가 주어졌을 때__읽으면__열 개 필드를 보존하고 차단된 쪽지도 거절하지 않은 채 답장 가능 여부를 돌려준다`(
        blocked: Boolean,
    ) {
        // given
        val note = StoredNote(
            NOTE_ID, 8L, 7L, " 원문\n쪽지 본문 ", "POST", Long.MAX_VALUE,
            NOW.minusSeconds(5), "INBOX", null, true,
        )

        every { members.get(7L) } returns CURRENT
        every { members.get(8L) } returns COUNTERPART
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(note)
        every { blocks.blocked(any(), any()) } returns blocked

        // when
        val result = query.read(7L, NOTE_ID)

        // then
        assertEquals(
            PrivateNoteView(
                NOTE_ID, " 원문\n쪽지 본문 ", "INBOX", NOW.minusSeconds(5),
                null, true, 8L, " 원문 닉네임 ", !blocked, "/community/" + Long.MAX_VALUE,
            ),
            result,
        )
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.find(7L, NOTE_ID, NOW)
            members.get(8L)
            blocks.blocked(7L, 8L)
        }
    }

    @Test
    fun `활성 수신자가 있는 보낸 쪽지가 주어졌을 때__읽으면__차단 조회 없이 수신자와 신고 링크를 보여준다`() {
        // given
        val note = StoredNote(
            NOTE_ID, 7L, Long.MAX_VALUE, "보낸 원문", "REPORT", Long.MAX_VALUE,
            NOW.minusSeconds(5), "SENT", NOW.minusSeconds(1), false,
        )

        every { members.get(7L) } returns CURRENT
        every { members.get(Long.MAX_VALUE) } returns ContactMember(Long.MAX_VALUE, "수신자", true, false)
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(note)

        // when
        val result = query.read(7L, NOTE_ID)

        // then
        assertEquals(
            PrivateNoteView(
                NOTE_ID, "보낸 원문", "SENT", NOW.minusSeconds(5), NOW.minusSeconds(1),
                false, Long.MAX_VALUE, "수신자", false, "/reports/" + Long.MAX_VALUE,
            ),
            result,
        )
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.find(7L, NOTE_ID, NOW)
            members.get(Long.MAX_VALUE)
        }
        confirmVerified(blocks)
    }

    @ParameterizedTest
    @EnumSource(CounterpartState::class)
    fun `상대가 null이거나 없거나 탈퇴 대기이거나 원문이 빈 이름일 때__읽으면__탈퇴 정책을 적용하고 활성 받은 쪽지만 차단을 확인한다`(
        state: CounterpartState,
    ) {
        // given
        val senderId: Long? = if (state == CounterpartState.NULL_ID) null else 8L
        val note = StoredNote(NOTE_ID, senderId, 7L, "본문", null, null, NOW, "INBOX", null, false)
        val active = state == CounterpartState.EMPTY_NAME || state == CounterpartState.SPACE_NAME
        val nickname = if (state == CounterpartState.EMPTY_NAME) "" else "  "

        every { members.get(7L) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(note)
        if (senderId != null) {
            every { members.get(senderId) } returns
                ContactMember(senderId, nickname, active, state == CounterpartState.PENDING)
        }
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = query.read(7L, NOTE_ID)

        // then
        assertEquals(
            PrivateNoteView(
                NOTE_ID, "본문", "INBOX", NOW, null, false,
                if (active) 8L else null, if (active) nickname else "탈퇴한 회원", active, null,
            ),
            result,
        )
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.find(7L, NOTE_ID, NOW)
            if (senderId != null) {
                members.get(senderId)
            }
            if (active) {
                blocks.blocked(7L, 8L)
            }
        }
    }

    @Test
    fun `호출자와 상대 조회 중 시간이 흘렀을 때__읽으면__호출자 조회 뒤 쪽지 조회 전에 한 번만 시각을 잡는다`() {
        // given
        var time = NOW.minusSeconds(1)

        every { members.get(7L) } answers {
            time = NOW
            CURRENT
        }
        every { clock.instant() } answers { time }
        every { notes.find(any(), any(), any()) } returns Optional.of(inbox())
        every { members.get(8L) } answers {
            time = NOW.plusSeconds(3600)
            COUNTERPART
        }
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = query.read(7L, NOTE_ID)

        // then
        assertEquals(NOW, result.createdAt)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.find(7L, NOTE_ID, NOW)
            members.get(8L)
            blocks.blocked(7L, 8L)
        }
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `호출자 시각 쪽지 상대 또는 차단 조회가 실패할 때__읽으면__같은 실패를 전파하고 이후 단계를 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.COUNTERPART ->
                ContactMemberUnavailableException(TransactionSystemException("private User commit"))
            FailurePhase.CLOCK -> IllegalStateException("private clock")
            FailurePhase.FIND -> DataAccessResourceFailureException("private note SQL")
            FailurePhase.BLOCK -> TransactionTimedOutException("private block timeout")
        }

        if (phase == FailurePhase.CALLER) {
            every { members.get(7L) } throws failure
        } else {
            every { members.get(7L) } returns CURRENT
        }
        if (phase == FailurePhase.COUNTERPART) {
            every { members.get(8L) } throws failure
        } else {
            every { members.get(8L) } returns COUNTERPART
        }
        if (phase == FailurePhase.CLOCK) {
            every { clock.instant() } throws failure
        } else {
            every { clock.instant() } returns NOW
        }
        if (phase == FailurePhase.FIND) {
            every { notes.find(any(), any(), any()) } throws failure
        } else {
            every { notes.find(any(), any(), any()) } returns Optional.of(inbox())
        }
        if (phase == FailurePhase.BLOCK) {
            every { blocks.blocked(any(), any()) } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, NOTE_ID) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.CALLER) {
                clock.instant()
            }
            if (phase == FailurePhase.FIND || phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK) {
                notes.find(7L, NOTE_ID, NOW)
            }
            if (phase == FailurePhase.COUNTERPART || phase == FailurePhase.BLOCK) {
                members.get(8L)
            }
            if (phase == FailurePhase.BLOCK) {
                blocks.blocked(7L, 8L)
            }
        }
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
