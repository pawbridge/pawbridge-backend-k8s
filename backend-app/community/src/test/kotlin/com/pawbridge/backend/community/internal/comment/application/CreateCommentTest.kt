package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class CreateCommentTest {
    private val posts = mockk<PostReader>()
    private val nicknames = mockk<UserNicknameQuery>()
    private val writer = mockk<CommentWriter>()
    private val create = CreateComment(posts, nicknames, writer)

    @ParameterizedTest
    @ValueSource(strings = ["PROTECTION", "ADOPTION", "COMMUNICATION", "MISSING", "REPORT"])
    fun `다른 작성자의 게시글이 어느 게시판에 있든__댓글을 작성하면__부모와 닉네임을 읽고 커밋된 댓글로 응답을 만든다`(
        board: String,
    ) {
        // given
        val parent = post(board)
        val committed = saved()

        every { posts.findById(any()) } returns Optional.of(parent)
        every { nicknames.findNickname(any()) } returns Optional.of("작성자")
        every { writer.create(any(), any(), any()) } returns committed

        // when
        val result = create.create(42L, 7L, CONTENT)

        // then
        assertEquals(CommentDetails(13L, 42L, 7L, "작성자", CONTENT, NOW, NOW), result)
        verifySequence {
            posts.findById(42L)
            nicknames.findNickname(7L)
            writer.create(42L, 7L, CONTENT)
        }
    }

    @Test
    fun `없거나 삭제된 부모 게시글일 때__댓글을 작성하면__닉네임 조회와 쓰기보다 먼저 찾을 수 없음으로 실패한다`() {
        // given
        every { posts.findById(any()) } returns Optional.empty()

        // when
        assertThrows(PostNotFoundException::class.java) { create.create(42L, 7L, CONTENT) }

        // then
        verify(exactly = 1) { posts.findById(42L) }
        confirmVerified(posts, nicknames, writer)
    }

    @Test
    fun `부모 게시글 조회가 실패할 때__댓글을 작성하면__원래 예외로 실패하고 닉네임 조회와 쓰기를 하지 않는다`() {
        // given
        val failure = IllegalStateException("parent read failed")

        every { posts.findById(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { create.create(42L, 7L, CONTENT) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { posts.findById(42L) }
        confirmVerified(posts, nicknames, writer)
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    fun `정해진 닉네임 조회 결과가 주어졌을 때__댓글을 작성하면__기존 응답 정책대로 댓글을 쓴다`(
        nickname: UserNicknameQuery,
        expected: String?,
    ) {
        // given
        val parent = post("COMMUNICATION")
        val committed = saved()

        every { posts.findById(any()) } returns Optional.of(parent)
        every { writer.create(any(), any(), any()) } returns committed

        // when
        val result = CreateComment(posts, nickname, writer).create(42L, 7L, CONTENT)

        // then
        assertEquals(CommentDetails(13L, 42L, 7L, expected, CONTENT, NOW, NOW), result)
        verifySequence {
            posts.findById(42L)
            writer.create(42L, 7L, CONTENT)
        }
    }

    @Test
    fun `닉네임 조회가 예상 밖으로 실패할 때__댓글을 작성하면__원래 예외로 실패하고 쓰기를 하지 않는다`() {
        // given
        val parent = post("COMMUNICATION")
        val failure = IllegalArgumentException("programming failure")

        every { posts.findById(any()) } returns Optional.of(parent)
        every { nicknames.findNickname(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { create.create(42L, 7L, CONTENT) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { posts.findById(42L) }
        verify(exactly = 1) { nicknames.findNickname(7L) }
        confirmVerified(posts, nicknames, writer)
    }

    @Test
    fun `쓰기 커밋이 실패할 때__댓글을 작성하면__읽기와 쓰기를 반복하지 않고 같은 예외를 전파한다`() {
        // given
        val parent = post("COMMUNICATION")
        val failure = IllegalStateException("commit outcome unknown")

        every { posts.findById(any()) } returns Optional.of(parent)
        every { nicknames.findNickname(any()) } returns Optional.of("작성자")
        every { writer.create(any(), any(), any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { create.create(42L, 7L, CONTENT) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            posts.findById(42L)
            nicknames.findNickname(7L)
            writer.create(42L, 7L, CONTENT)
        }
    }

    private fun post(board: String): PostSnapshot =
        PostSnapshot(42L, 99L, "다른 작성자의 게시글", "원문", board, listOf(), NOW, NOW)

    private fun saved(): CommentSnapshot = CommentSnapshot(13L, 42L, 7L, CONTENT, NOW, NOW)

    companion object {
        private val NOW = LocalDateTime.of(2026, 10, 19, 12, 30, 10, 123456789)
        private const val CONTENT = "  댓글 본문\n "

        @JvmStatic
        fun nicknameOutcomes(): Stream<Arguments> =
            Stream.of(
                Arguments.of(nicknameQuery { Optional.of("작성자") }, "작성자"),
                Arguments.of(nicknameQuery { Optional.empty() }, "사용자7"),
                Arguments.of(
                    nicknameQuery {
                        throw UserNicknameUnavailableException(IllegalStateException("unavailable"))
                    },
                    "사용자7",
                ),
                Arguments.of(nicknameQuery { Optional.of("") }, null),
                Arguments.of(nicknameQuery { Optional.of("  \t ") }, "  \t "),
            )

        private fun nicknameQuery(lookup: (Long) -> Optional<String>): UserNicknameQuery =
            object : UserNicknameQuery {
                override fun findNickname(userId: Long): Optional<String> = lookup(userId)
            }
    }
}
