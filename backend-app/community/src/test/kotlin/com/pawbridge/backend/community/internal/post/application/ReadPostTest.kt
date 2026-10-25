package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.support.postReader
import com.pawbridge.backend.community.support.userNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class ReadPostTest {
    @Test
    fun given__post_and_nickname__when__read__then__details_preserve_post_fields() {
        val post = createPost()
        val readPost = ReadPost(
            postReader { postId ->
                assertEquals(post.postId(), postId)
                Optional.of(post)
            },
            userNicknameQuery { authorId ->
                assertEquals(post.authorId(), authorId)
                Optional.of("반려친구")
            },
        )

        val details = readPost.read(post.postId())

        assertEquals(
            PostDetails(
                post.postId(),
                post.authorId(),
                "반려친구",
                post.title(),
                post.content(),
                post.boardType(),
                post.imageUrls(),
                post.createdAt(),
                post.updatedAt(),
            ),
            details,
        )
    }

    @Test
    fun given__missing_user__when__read__then__author_id_fallback_is_returned() {
        val readPost = createReader(userNicknameQuery { Optional.empty() })

        assertEquals("사용자7", readPost.read(42L).authorNickname)
    }

    @Test
    fun given__nickname_unavailable__when__read__then__author_id_fallback_is_returned() {
        val readPost = createReader(
            userNicknameQuery { throw UserNicknameUnavailableException(IllegalStateException("unavailable")) },
        )

        assertEquals("사용자7", readPost.read(42L).authorNickname)
    }

    @Test
    fun given__empty_nickname__when__read__then__legacy_empty_body_is_represented_as_null() {
        val readPost = createReader(userNicknameQuery { Optional.of("") })

        assertNull(readPost.read(42L).authorNickname)
    }

    @Test
    fun given__whitespace_nickname__when__read__then__whitespace_is_preserved() {
        val readPost = createReader(userNicknameQuery { Optional.of("  \t ") })

        assertEquals("  \t ", readPost.read(42L).authorNickname)
    }

    @Test
    fun given__missing_post__when__read__then__not_found_prevents_nickname_lookup() {
        val readPost = ReadPost(
            postReader { Optional.empty() },
            userNicknameQuery { throw AssertionError("Nickname lookup must follow a successful post lookup") },
        )

        assertThrows(PostNotFoundException::class.java) { readPost.read(-1L) }
    }

    @Test
    fun given__post_read_failure__when__read__then__failure_propagates_before_nickname_lookup() {
        val failure = IllegalStateException("post mapping failed")
        val readPost = ReadPost(
            postReader { throw failure },
            userNicknameQuery { throw AssertionError("Nickname lookup must follow a successful post lookup") },
        )

        assertSame(failure, assertThrows(IllegalStateException::class.java) { readPost.read(42L) })
    }

    @Test
    fun given__unexpected_nickname_failure__when__read__then__failure_is_not_hidden_by_fallback() {
        val failure = IllegalArgumentException("programming failure")
        val readPost = createReader(userNicknameQuery { throw failure })

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { readPost.read(42L) })
    }

    private fun createReader(nicknameQuery: UserNicknameQuery): ReadPost =
        ReadPost(postReader { Optional.of(createPost()) }, nicknameQuery)

    private fun createPost(): PostSnapshot =
        PostSnapshot(
            42L,
            7L,
            "함께 찾아주세요",
            "게시글 본문",
            "MISSING",
            listOf("https://example.test/한글.png", null, "https://example.test/한글.png"),
            LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
            LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000),
        )
}
