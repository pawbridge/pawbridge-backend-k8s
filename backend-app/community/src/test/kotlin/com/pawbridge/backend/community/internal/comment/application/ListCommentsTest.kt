package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.LocalDateTime
import java.util.Optional

class ListCommentsTest {
    private val commentListReader = mock(CommentListReader::class.java)
    private val nicknameQuery = mock(UserNicknameQuery::class.java)
    private val listComments = ListComments(commentListReader, nicknameQuery)

    @Test
    fun given__no_comments__when__read__then__empty_list_without_nickname_lookup() {
        whenever(commentListReader.findByPostId(42L)).thenReturn(listOf())

        assertEquals(listOf<CommentDetails>(), listComments.read(42L))
        verifyNoInteractions(nicknameQuery)
    }

    @Test
    fun given__ordered_comments_with_repeated_author__when__read__then__each_row_is_enriched_in_order() {
        val first = comment(13L, 7L)
        val second = comment(15L, 9L)
        val third = comment(12L, 7L)
        whenever(commentListReader.findByPostId(42L)).thenReturn(listOf(first, second, third))
        whenever(nicknameQuery.findNickname(7L))
            .thenReturn(Optional.of("첫 닉네임"))
            .thenReturn(Optional.of("다음 닉네임"))
        whenever(nicknameQuery.findNickname(9L)).thenReturn(Optional.of("다른 작성자"))

        assertEquals(
            listOf(details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
            listComments.read(42L),
        )
        val order = inOrder(commentListReader, nicknameQuery)
        order.verify(commentListReader).findByPostId(42L)
        order.verify(nicknameQuery).findNickname(7L)
        order.verify(nicknameQuery).findNickname(9L)
        order.verify(nicknameQuery).findNickname(7L)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__missing_and_unavailable_nicknames__when__read__then__only_each_affected_row_uses_fallback() {
        val missing = comment(13L, 7L)
        val unavailable = comment(12L, 8L)
        val available = comment(11L, 7L)
        whenever(commentListReader.findByPostId(42L)).thenReturn(listOf(missing, unavailable, available))
        whenever(nicknameQuery.findNickname(7L))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of("회복한 닉네임"))
        whenever(nicknameQuery.findNickname(8L))
            .thenThrow(UserNicknameUnavailableException(IllegalStateException("unavailable")))

        assertEquals(
            listOf(details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
            listComments.read(42L),
        )
    }

    @Test
    fun given__empty_and_whitespace_nicknames__when__read__then__empty_is_null_and_whitespace_is_preserved() {
        val empty = comment(13L, 7L)
        val whitespace = comment(12L, 8L)
        whenever(commentListReader.findByPostId(42L)).thenReturn(listOf(empty, whitespace))
        whenever(nicknameQuery.findNickname(7L)).thenReturn(Optional.of(""))
        whenever(nicknameQuery.findNickname(8L)).thenReturn(Optional.of("  \t "))

        assertEquals(listOf(details(empty, null), details(whitespace, "  \t ")), listComments.read(42L))
    }

    @Test
    fun given__comment_read_failure__when__read__then__original_failure_prevents_nickname_lookup() {
        val failure = IllegalStateException("comment mapping failed")
        whenever(commentListReader.findByPostId(42L)).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { listComments.read(42L) })
        verifyNoInteractions(nicknameQuery)
    }

    @Test
    fun given__unexpected_nickname_failure__when__read__then__whole_list_fails_and_later_rows_are_not_queried() {
        whenever(commentListReader.findByPostId(42L))
            .thenReturn(listOf(comment(13L, 7L), comment(12L, 8L), comment(11L, 9L)))
        whenever(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("정상"))
        val failure = IllegalArgumentException("programming failure")
        whenever(nicknameQuery.findNickname(8L)).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { listComments.read(42L) })
        val order = inOrder(nicknameQuery)
        order.verify(nicknameQuery).findNickname(7L)
        order.verify(nicknameQuery).findNickname(8L)
        order.verifyNoMoreInteractions()
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
