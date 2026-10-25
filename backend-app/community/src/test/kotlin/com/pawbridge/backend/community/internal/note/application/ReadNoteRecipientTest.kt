package com.pawbridge.backend.community.internal.note.application

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

class ReadNoteRecipientTest {
    private val members = mockk<ContactMemberQuery>()
    private val blocks = mockk<NoteBlockReader>()
    private val query = ReadNoteRecipient(members, blocks)

    @Test
    fun `활성이고 서로 차단하지 않은 회원이 주어졌을 때__읽으면__현재 회원 차단 대상 순서로 확인한 뒤 회원 정보를 돌려준다`() {
        // given
        every { members.get(7L) } returns CURRENT
        every { members.get(99L) } returns RECIPIENT
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = query.read(7L, 99L)

        // then
        assertSame(RECIPIENT, result)
        verifySequence {
            members.get(7L)
            blocks.blocked(7L, 99L)
            members.get(99L)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `0 이하의 현재 회원 ID가 주어졌을 때__읽으면__회원 조회와 차단 조회 전에 로그인 거절로 끝난다`(currentId: Long) {
        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(currentId, 99L) }

        // then
        assertEquals(RecipientRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `현재 회원이 없거나 탈퇴 대기일 때__자기 자신을 읽으면__본인 확인과 저장소 조회보다 비활성 거절이 먼저다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(7L, 7L) }

        // then
        assertEquals(RecipientRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, blocks)
    }

    @Test
    fun `활성 현재 회원이 자기 자신을 대상으로 할 때__읽으면__차단 조회나 두 번째 회원 조회 없이 거절한다`() {
        // given
        every { members.get(any()) } returns CURRENT

        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(7L, 7L) }

        // then
        assertEquals(RecipientRejectedException.Reason.BLOCKED, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, blocks)
    }

    @Test
    fun `두 회원 사이에 차단이 있을 때__읽으면__대상 회원 조회 전에 거절한다`() {
        // given
        every { members.get(any()) } returns CURRENT
        every { blocks.blocked(any(), any()) } returns true

        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(7L, 99L) }

        // then
        assertEquals(RecipientRejectedException.Reason.BLOCKED, failure.reason())
        verifySequence {
            members.get(7L)
            blocks.blocked(7L, 99L)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `0 이하의 대상 ID가 주어졌을 때__읽으면__현재 회원 조회와 차단 조회 뒤에 로그인 거절한다`(targetId: Long) {
        // given
        every { members.get(any()) } returns CURRENT
        every { blocks.blocked(any(), any()) } returns false

        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(7L, targetId) }

        // then
        assertEquals(RecipientRejectedException.Reason.LOGIN, failure.reason())
        verifySequence {
            members.get(7L)
            blocks.blocked(7L, targetId)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `대상 회원이 없거나 탈퇴 대기일 때__읽으면__현재 회원 조회와 차단 조회 뒤에만 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(7L) } returns CURRENT
        every { members.get(99L) } returns ContactMember(99L, "탈퇴한 회원", false, pending)
        every { blocks.blocked(any(), any()) } returns false

        // when
        val failure = assertThrows(RecipientRejectedException::class.java) { query.read(7L, 99L) }

        // then
        assertEquals(RecipientRejectedException.Reason.INACTIVE, failure.reason())
        verifySequence {
            members.get(7L)
            blocks.blocked(7L, 99L)
            members.get(99L)
        }
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `조회가 실패할 때__읽으면__원래 실패를 보존하고 이후 검증이나 조회 전에 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val targetId = if (phase == FailurePhase.BLOCK_DAO || phase == FailurePhase.BLOCK_RUNTIME) 0L else 99L
        val failure: RuntimeException = when (phase) {
            FailurePhase.CURRENT, FailurePhase.TARGET ->
                ContactMemberUnavailableException(IllegalStateException("private User failure"))
            FailurePhase.BLOCK_DAO -> DataAccessResourceFailureException("private block SQL failure")
            FailurePhase.BLOCK_RUNTIME -> IllegalStateException("private block runtime")
        }

        when (phase) {
            FailurePhase.CURRENT -> every { members.get(any()) } throws failure
            FailurePhase.TARGET -> {
                every { members.get(7L) } returns CURRENT
                every { members.get(99L) } throws failure
                every { blocks.blocked(any(), any()) } returns false
            }
            FailurePhase.BLOCK_DAO, FailurePhase.BLOCK_RUNTIME -> {
                every { members.get(any()) } returns CURRENT
                every { blocks.blocked(any(), any()) } throws failure
            }
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, targetId) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.CURRENT) {
                blocks.blocked(7L, targetId)
            }
            if (phase == FailurePhase.TARGET) {
                members.get(99L)
            }
        }
    }

    enum class FailurePhase { CURRENT, BLOCK_DAO, BLOCK_RUNTIME, TARGET }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
        val RECIPIENT = ContactMember(99L, "  원문 닉네임  ", true, false)
    }
}
