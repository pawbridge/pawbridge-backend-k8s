package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class ListCommentsTest {
    private val commentListReader = mockk<CommentListReader>()
    private val nicknameQuery = mockk<UserNicknameQuery>()
    private val listComments = ListComments(commentListReader, nicknameQuery)

    @Test
    fun `댓글이 없을 때__조회하면__닉네임 조회 없이 빈 목록을 돌려준다`() {
        // given
        every { commentListReader.findByPostId(any()) } returns listOf()

        // when
        val result = listComments.read(42L)

        // then
        assertEquals(listOf<CommentDetails>(), result)
        verify(exactly = 1) { commentListReader.findByPostId(42L) }
        confirmVerified(commentListReader, nicknameQuery)
    }

    @Test
    fun `같은 작성자가 반복되는 정렬된 댓글이 있을 때__조회하면__행마다 순서대로 닉네임을 붙인다`() {
        // given
        val first = comment(13L, 7L)
        val second = comment(15L, 9L)
        val third = comment(12L, 7L)

        every { commentListReader.findByPostId(any()) } returns listOf(first, second, third)
        every { nicknameQuery.findNickname(7L) } returnsMany listOf(
            Optional.of("첫 닉네임"),
            Optional.of("다음 닉네임"),
        )
        every { nicknameQuery.findNickname(9L) } returns Optional.of("다른 작성자")

        // when
        val result = listComments.read(42L)

        // then
        assertEquals(
            listOf(details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
            result,
        )
        verifySequence {
            commentListReader.findByPostId(42L)
            nicknameQuery.findNickname(7L)
            nicknameQuery.findNickname(9L)
            nicknameQuery.findNickname(7L)
        }
    }

    @Test
    fun `닉네임이 없거나 조회할 수 없는 행이 있을 때__조회하면__영향받은 행만 대체 닉네임을 쓴다`() {
        // given
        val missing = comment(13L, 7L)
        val unavailable = comment(12L, 8L)
        val available = comment(11L, 7L)

        every { commentListReader.findByPostId(any()) } returns listOf(missing, unavailable, available)
        every { nicknameQuery.findNickname(7L) } returnsMany listOf(
            Optional.empty(),
            Optional.of("회복한 닉네임"),
        )
        every { nicknameQuery.findNickname(8L) } throws
            UserNicknameUnavailableException(IllegalStateException("unavailable"))

        // when
        val result = listComments.read(42L)

        // then
        assertEquals(
            listOf(details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
            result,
        )
    }

    @Test
    fun `빈 닉네임과 공백 닉네임이 있을 때__조회하면__빈 값은 null로 공백은 그대로 둔다`() {
        // given
        val empty = comment(13L, 7L)
        val whitespace = comment(12L, 8L)

        every { commentListReader.findByPostId(any()) } returns listOf(empty, whitespace)
        every { nicknameQuery.findNickname(7L) } returns Optional.of("")
        every { nicknameQuery.findNickname(8L) } returns Optional.of("  \t ")

        // when
        val result = listComments.read(42L)

        // then
        assertEquals(listOf(details(empty, null), details(whitespace, "  \t ")), result)
    }

    @Test
    fun `댓글 조회가 실패할 때__조회하면__원래 예외로 실패하고 닉네임을 조회하지 않는다`() {
        // given
        val failure = IllegalStateException("comment mapping failed")

        every { commentListReader.findByPostId(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { listComments.read(42L) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { commentListReader.findByPostId(42L) }
        confirmVerified(commentListReader, nicknameQuery)
    }

    @Test
    fun `닉네임 조회가 예상 밖으로 실패할 때__조회하면__전체가 실패하고 뒤 행은 조회하지 않는다`() {
        // given
        val failure = IllegalArgumentException("programming failure")

        every { commentListReader.findByPostId(any()) } returns
            listOf(comment(13L, 7L), comment(12L, 8L), comment(11L, 9L))
        every { nicknameQuery.findNickname(7L) } returns Optional.of("정상")
        every { nicknameQuery.findNickname(8L) } throws failure

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { listComments.read(42L) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            nicknameQuery.findNickname(7L)
            nicknameQuery.findNickname(8L)
        }
    }

    private fun details(comment: CommentSnapshot, nickname: String?): CommentDetails =
        CommentDetails(
            comment.commentId,
            comment.postId,
            comment.authorId,
            nickname,
            comment.content,
            comment.createdAt,
            comment.updatedAt,
        )

    private fun comment(commentId: Long, authorId: Long): CommentSnapshot =
        CommentSnapshot(
            commentId,
            42L,
            authorId,
            "댓글 본문 $commentId",
            LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
            LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000),
        )
}
