package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.BlockPage.BlockView
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Instant

class ListNoteBlocksTest {
    private val members = mockk<ContactMemberQuery>()
    private val blocks = mockk<NoteBlockListReader>()
    private val contactMembers = mockk<ContactMembersQuery>()
    private val query = ListNoteBlocks(members, blocks, contactMembers)

    @ParameterizedTest
    @ValueSource(ints = [-1, 10001])
    fun `잘못된 쪽 번호와 현재 회원 ID가 주어졌을 때__읽으면__로그인이나 어떤 의존성보다 먼저 쪽 번호를 거절한다`(page: Int) {
        // when
        val failure = assertThrows(NoteBlockListRejectedException::class.java) { query.read(0L, page) }

        // then
        assertEquals(NoteBlockListRejectedException.Reason.INVALID_PAGE, failure.reason())
        confirmVerified(members, blocks, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 10000])
    fun `내용보다 뒤인 유효한 쪽 경계가 주어졌을 때__읽으면__일괄 조회 없이 요청한 쪽과 개수와 순서를 유지한다`(
        page: Int,
    ) {
        // given
        every { members.get(any()) } returns CURRENT
        every { blocks.count(any()) } returns 11L
        every { blocks.read(any(), any()) } returns listOf()

        // when
        val result = query.read(7L, page)

        // then
        assertEquals(BlockPage(listOf(), 11L, 2, page), result)
        verifySequence {
            members.get(7L)
            blocks.count(7L)
            blocks.read(7L, page)
        }
        confirmVerified(contactMembers)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `유효한 쪽과 잘못된 현재 회원 ID가 주어졌을 때__읽으면__회원 조회와 차단 조회 전에 로그인 거절한다`(
        currentUserId: Long,
    ) {
        // when
        val failure = assertThrows(NoteBlockListRejectedException::class.java) { query.read(currentUserId, 0) }

        // then
        assertEquals(NoteBlockListRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, blocks, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `현재 회원이 없거나 탈퇴 대기일 때__읽으면__개수와 목록과 일괄 조회 전에 비활성으로 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(NoteBlockListRejectedException::class.java) { query.read(7L, 0) }

        // then
        assertEquals(NoteBlockListRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, blocks, contactMembers)
    }

    @Test
    fun `정렬된 차단 목록과 순서 없는 완전한 연락처가 주어졌을 때__읽으면__일괄 조회를 한 번 하고 대상 ID와 원문 이름과 시각과 쪽을 보존한다`() {
        // given
        val first = Instant.parse("2026-10-19T14:59:59.123456Z")
        val second = Instant.parse("1969-12-31T23:59:59.000001Z")
        val page = listOf(
            Block(90L, first),
            Block(80L, first),
            Block(70L, first),
            Block(60L, second),
            Block(50L, second),
        )

        every { members.get(any()) } returns CURRENT
        every { blocks.count(any()) } returns 15L
        every { blocks.read(any(), any()) } returns page
        every { contactMembers.getAll(any()) } returns listOf(
            ContactMember(50L, "탈퇴한 회원", false, true),
            ContactMember(60L, "탈퇴한 회원", false, false),
            ContactMember(70L, "  ", true, false),
            ContactMember(80L, "", true, false),
            ContactMember(90L, " 원문 닉네임 ", true, false),
        )

        // when
        val result = query.read(7L, 1)

        // then
        assertEquals(
            BlockPage(
                listOf(
                    BlockView(90L, " 원문 닉네임 ", first),
                    BlockView(80L, "", first),
                    BlockView(70L, "  ", first),
                    BlockView(60L, "탈퇴한 회원", second),
                    BlockView(50L, "탈퇴한 회원", second),
                ),
                15L,
                2,
                1,
            ),
            result,
        )
        verifySequence {
            members.get(7L)
            blocks.count(7L)
            blocks.read(7L, 1)
            contactMembers.getAll(listOf(90L, 80L, 70L, 60L, 50L))
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 17L])
    fun `개수가 0인 경우를 포함한 빈 쪽이 주어졌을 때__읽으면__쪽은 한 번 읽고 일괄 조회만 건너뛴다`(
        count: Long,
    ) {
        // given
        every { members.get(any()) } returns CURRENT
        every { blocks.count(any()) } returns count
        every { blocks.read(any(), any()) } returns listOf()

        // when
        val result = query.read(7L, 1)

        // then
        assertEquals(BlockPage(listOf(), count, if (count == 0L) 0 else 2, 1), result)
        verifySequence {
            members.get(7L)
            blocks.count(7L)
            blocks.read(7L, 1)
        }
        confirmVerified(contactMembers)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `호출자 개수 목록 또는 일괄 조회가 실패할 때__읽으면__실패 원인을 보존하고 이후 단계를 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER, FailurePhase.BATCH ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.COUNT -> DataAccessResourceFailureException("private count SQL")
            FailurePhase.PAGE -> TransactionTimedOutException("private list timeout")
        }

        when (phase) {
            FailurePhase.CALLER -> every { members.get(any()) } throws failure
            FailurePhase.COUNT -> {
                every { members.get(any()) } returns CURRENT
                every { blocks.count(any()) } throws failure
            }
            FailurePhase.PAGE -> {
                every { members.get(any()) } returns CURRENT
                every { blocks.count(any()) } returns 1L
                every { blocks.read(any(), any()) } throws failure
            }
            FailurePhase.BATCH -> {
                every { members.get(any()) } returns CURRENT
                every { blocks.count(any()) } returns 1L
                every { blocks.read(any(), any()) } returns listOf(Block(8L, Instant.EPOCH))
                every { contactMembers.getAll(any()) } throws failure
            }
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, 0) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.CALLER) {
                blocks.count(7L)
            }
            if (phase == FailurePhase.PAGE || phase == FailurePhase.BATCH) {
                blocks.read(7L, 0)
            }
            if (phase == FailurePhase.BATCH) {
                contactMembers.getAll(listOf(8L))
            }
        }
    }

    enum class FailurePhase { CALLER, COUNT, PAGE, BATCH }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
    }
}
