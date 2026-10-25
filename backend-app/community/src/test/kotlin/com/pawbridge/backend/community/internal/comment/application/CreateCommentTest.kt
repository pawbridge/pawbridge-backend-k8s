package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class CreateCommentTest {
    private val posts = mock(PostReader::class.java)
    private val nicknames = mock(UserNicknameQuery::class.java)
    private val writer = mock(CommentWriter::class.java)
    private val create = CreateComment(posts, nicknames, writer)

    @ParameterizedTest
    @ValueSource(strings = ["PROTECTION", "ADOPTION", "COMMUNICATION", "MISSING", "REPORT"])
    fun given__another_authors_post_in_any_board__when__create__then__parent_nickname_and_committed_comment_determine_response(
        board: String,
    ) {
        whenever(posts.findById(42L)).thenReturn(Optional.of(post(board)))
        whenever(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"))
        whenever(writer.create(42L, 7L, CONTENT)).thenReturn(saved())

        assertEquals(
            CommentDetails(13L, 42L, 7L, "작성자", CONTENT, NOW, NOW),
            create.create(42L, 7L, CONTENT),
        )
        val order = inOrder(posts, nicknames, writer)
        order.verify(posts).findById(42L)
        order.verify(nicknames).findNickname(7L)
        order.verify(writer).create(42L, 7L, CONTENT)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__absent_or_deleted_parent__when__create__then__not_found_precedes_nickname_and_write() {
        whenever(posts.findById(42L)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) { create.create(42L, 7L, CONTENT) }
        verifyNoInteractions(nicknames, writer)
    }

    @Test
    fun given__parent_read_failure__when__create__then__original_failure_prevents_nickname_and_write() {
        val failure = IllegalStateException("parent read failed")
        whenever(posts.findById(42L)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { create.create(42L, 7L, CONTENT) },
        )
        verifyNoInteractions(nicknames, writer)
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    fun given__defined_nickname_outcome__when__create__then__comment_is_written_with_existing_response_policy(
        nickname: UserNicknameQuery,
        expected: String?,
    ) {
        whenever(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")))
        whenever(writer.create(42L, 7L, CONTENT)).thenReturn(saved())

        assertEquals(
            CommentDetails(13L, 42L, 7L, expected, CONTENT, NOW, NOW),
            CreateComment(posts, nickname, writer).create(42L, 7L, CONTENT),
        )
        val order = inOrder(posts, writer)
        order.verify(posts).findById(42L)
        order.verify(writer).create(42L, 7L, CONTENT)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__unexpected_nickname_failure__when__create__then__original_failure_prevents_write() {
        whenever(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")))
        val failure = IllegalArgumentException("programming failure")
        whenever(nicknames.findNickname(7L)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) { create.create(42L, 7L, CONTENT) },
        )
        verifyNoInteractions(writer)
    }

    @Test
    fun given__writer_commit_failure__when__create__then__failure_propagates_without_repeating_reads_or_write() {
        whenever(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")))
        whenever(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"))
        val failure = IllegalStateException("commit outcome unknown")
        whenever(writer.create(42L, 7L, CONTENT)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { create.create(42L, 7L, CONTENT) },
        )
        val order = inOrder(posts, nicknames, writer)
        order.verify(posts).findById(42L)
        order.verify(nicknames).findNickname(7L)
        order.verify(writer).create(42L, 7L, CONTENT)
        order.verifyNoMoreInteractions()
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
