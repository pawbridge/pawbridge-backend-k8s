package com.pawbridge.backend.community.internal.post.application

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

class ListPostsTest {
    private val postListReader = mock(PostListReader::class.java)
    private val nicknameQuery = mock(UserNicknameQuery::class.java)
    private val listPosts = ListPosts(postListReader, nicknameQuery)

    @Test
    fun given__no_posts__when__read__then__empty_list_without_nickname_lookup() {
        whenever(postListReader.findAll()).thenReturn(listOf())

        assertEquals(listOf<PostDetails>(), listPosts.read())
        verifyNoInteractions(nicknameQuery)
    }

    @Test
    fun given__ordered_posts_with_repeated_author__when__read__then__each_row_is_enriched_in_order() {
        val first = post(42L, 7L)
        val second = post(41L, 9L)
        val third = post(40L, 7L)
        whenever(postListReader.findAll()).thenReturn(listOf(first, second, third))
        whenever(nicknameQuery.findNickname(7L))
            .thenReturn(Optional.of("첫 닉네임"))
            .thenReturn(Optional.of("다음 닉네임"))
        whenever(nicknameQuery.findNickname(9L)).thenReturn(Optional.of("다른 작성자"))

        assertEquals(
            listOf(details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
            listPosts.read(),
        )
        val order = inOrder(postListReader, nicknameQuery)
        order.verify(postListReader).findAll()
        order.verify(nicknameQuery).findNickname(7L)
        order.verify(nicknameQuery).findNickname(9L)
        order.verify(nicknameQuery).findNickname(7L)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__missing_and_unavailable_nicknames__when__read__then__only_each_affected_row_uses_fallback() {
        val missing = post(42L, 7L)
        val unavailable = post(41L, 8L)
        val available = post(40L, 7L)
        whenever(postListReader.findAll()).thenReturn(listOf(missing, unavailable, available))
        whenever(nicknameQuery.findNickname(7L))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of("회복한 닉네임"))
        whenever(nicknameQuery.findNickname(8L))
            .thenThrow(UserNicknameUnavailableException(IllegalStateException("unavailable")))

        assertEquals(
            listOf(details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
            listPosts.read(),
        )
    }

    @Test
    fun given__empty_and_whitespace_nicknames__when__read__then__empty_is_null_and_whitespace_is_preserved() {
        val empty = post(42L, 7L)
        val whitespace = post(41L, 8L)
        whenever(postListReader.findAll()).thenReturn(listOf(empty, whitespace))
        whenever(nicknameQuery.findNickname(7L)).thenReturn(Optional.of(""))
        whenever(nicknameQuery.findNickname(8L)).thenReturn(Optional.of("  \t "))

        assertEquals(listOf(details(empty, null), details(whitespace, "  \t ")), listPosts.read())
    }

    @Test
    fun given__post_read_failure__when__read__then__original_failure_prevents_nickname_lookup() {
        val failure = IllegalStateException("post mapping failed")
        whenever(postListReader.findAll()).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { listPosts.read() })
        verifyNoInteractions(nicknameQuery)
    }

    @Test
    fun given__unexpected_nickname_failure__when__read__then__whole_list_fails_and_later_rows_are_not_queried() {
        whenever(postListReader.findAll()).thenReturn(listOf(post(42L, 7L), post(41L, 8L), post(40L, 9L)))
        whenever(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("정상"))
        val failure = IllegalArgumentException("programming failure")
        whenever(nicknameQuery.findNickname(8L)).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { listPosts.read() })
        val order = inOrder(nicknameQuery)
        order.verify(nicknameQuery).findNickname(7L)
        order.verify(nicknameQuery).findNickname(8L)
        order.verifyNoMoreInteractions()
    }

    private fun details(post: PostSnapshot, nickname: String?): PostDetails =
        PostDetails(
            post.postId(),
            post.authorId(),
            nickname,
            post.title(),
            post.content(),
            post.boardType(),
            post.imageUrls(),
            post.createdAt(),
            post.updatedAt(),
        )

    private fun post(postId: Long, authorId: Long): PostSnapshot =
        PostSnapshot(
            postId,
            authorId,
            "함께 찾아주세요 $postId",
            "게시글 본문 $postId",
            "MISSING",
            listOf("https://example.test/한글.png", null, "https://example.test/한글.png"),
            LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
            LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000),
        )
}
