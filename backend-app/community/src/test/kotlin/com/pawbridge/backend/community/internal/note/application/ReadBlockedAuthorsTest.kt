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
import java.util.Collections

class ReadBlockedAuthorsTest {
    private val members = mockk<ContactMemberQuery>()
    private val blocks = mockk<NoteBlockStatusReader>()
    private val query = ReadBlockedAuthors(members, blocks)

    @Test
    fun `중복과 자기 자신과 최댓값 ID가 섞인 유효한 작성자 목록이 주어졌을 때__읽으면__현재 회원을 한 번 확인하고 대상 조회 없이 중복 없는 ID를 넘긴다`() {
        // given
        val storedIds = listOf(99L, Long.MAX_VALUE)

        every { members.get(any()) } returns CURRENT
        every { blocks.blockedAuthors(any(), any()) } returns storedIds

        // when
        val result = query.read(7L, listOf(Long.MAX_VALUE, 7L, 99L, Long.MAX_VALUE, 99L))

        // then
        assertSame(storedIds, result.memberIds)
        verifySequence {
            members.get(7L)
            blocks.blockedAuthors(7L, listOf(Long.MAX_VALUE, 7L, 99L))
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidInput::class)
    fun `잘못된 원본 목록과 현재 회원 ID가 주어졌을 때__읽으면__로그인이나 어떤 조회보다 먼저 잘못된 회원 목록으로 거절한다`(
        input: InvalidInput,
    ) {
        // given
        val ids: List<Long?>? = when (input) {
            InvalidInput.NULL -> null
            InvalidInput.EMPTY -> listOf()
            InvalidInput.NULL_ELEMENT -> listOf(8L, null, 9L)
            InvalidInput.ZERO -> listOf(8L, 0L)
            InvalidInput.NEGATIVE -> listOf(-1L, 8L)
            InvalidInput.DISTINCT_101 -> (1L..101L).toList()
            InvalidInput.DUPLICATE_101 -> Collections.nCopies(101, 8L)
        }

        // when
        val failure = assertThrows(BlockStatusRejectedException::class.java) { query.read(-1L, ids) }

        // then
        assertEquals(BlockStatusRejectedException.Reason.INVALID_MEMBERS, failure.reason())
        confirmVerified(members, blocks)
    }

    @ParameterizedTest
    @EnumSource(ValidBoundary::class)
    fun `원본 ID가 1개 또는 100개일 때__읽으면__경계를 허용하고 현재 회원 조회 뒤에만 중복을 없앤다`(
        boundary: ValidBoundary,
    ) {
        // given
        val ids: List<Long> = when (boundary) {
            ValidBoundary.ONE -> listOf(8L)
            ValidBoundary.DISTINCT_100 -> (1L..100L).toList()
            ValidBoundary.DUPLICATE_100 -> Collections.nCopies(100, 8L)
        }
        val distinctIds = ids.distinct()

        every { members.get(any()) } returns CURRENT
        every { blocks.blockedAuthors(any(), any()) } returns listOf()

        // when
        val result = query.read(7L, ids)

        // then
        assertEquals(listOf<Long>(), result.memberIds)
        verifySequence {
            members.get(7L)
            blocks.blockedAuthors(7L, distinctIds)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `유효한 목록과 잘못된 현재 회원 ID가 주어졌을 때__읽으면__회원 조회와 차단 조회 전에 로그인 거절한다`(
        currentId: Long,
    ) {
        // when
        val failure = assertThrows(BlockStatusRejectedException::class.java) { query.read(currentId, listOf(8L)) }

        // then
        assertEquals(BlockStatusRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `현재 회원이 없거나 탈퇴 대기일 때__읽으면__차단 조회 전에 비활성으로 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(BlockStatusRejectedException::class.java) { query.read(7L, listOf(8L)) }

        // then
        assertEquals(BlockStatusRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `회원 조회 또는 차단 조회가 실패할 때__읽으면__실패 원인을 보존하고 이후 조회를 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            FailurePhase.USER ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.BLOCK_DAO -> DataAccessResourceFailureException("private block SQL")
            FailurePhase.BLOCK_RUNTIME -> IllegalStateException("private block runtime")
        }

        if (phase == FailurePhase.USER) {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns CURRENT
            every { blocks.blockedAuthors(any(), any()) } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, listOf(8L, 8L)) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.USER) {
                blocks.blockedAuthors(7L, listOf(8L))
            }
        }
    }

    enum class InvalidInput { NULL, EMPTY, NULL_ELEMENT, ZERO, NEGATIVE, DISTINCT_101, DUPLICATE_101 }

    enum class ValidBoundary { ONE, DISTINCT_100, DUPLICATE_100 }

    enum class FailurePhase { USER, BLOCK_DAO, BLOCK_RUNTIME }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
    }
}
