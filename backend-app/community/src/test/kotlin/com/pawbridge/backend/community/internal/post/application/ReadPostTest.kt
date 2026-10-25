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
    fun `게시글과 닉네임이 있을 때__조회하면__게시글 필드를 그대로 담은 상세를 돌려준다`() {
        // given
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

        // when
        val details = readPost.read(post.postId())

        // then
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
    fun `작성자가 없을 때__조회하면__작성자 번호 기반 대체 이름을 돌려준다`() {
        // given
        val readPost = createReader(userNicknameQuery { Optional.empty() })

        // when
        val details = readPost.read(42L)

        // then
        assertEquals("사용자7", details.authorNickname)
    }

    @Test
    fun `닉네임을 조회할 수 없을 때__조회하면__작성자 번호 기반 대체 이름을 돌려준다`() {
        // given
        val readPost = createReader(
            userNicknameQuery { throw UserNicknameUnavailableException(IllegalStateException("unavailable")) },
        )

        // when
        val details = readPost.read(42L)

        // then
        assertEquals("사용자7", details.authorNickname)
    }

    @Test
    fun `닉네임이 빈 문자열일 때__조회하면__기존 동작대로 null로 나타낸다`() {
        // given
        val readPost = createReader(userNicknameQuery { Optional.of("") })

        // when
        val details = readPost.read(42L)

        // then
        assertNull(details.authorNickname)
    }

    @Test
    fun `닉네임이 공백일 때__조회하면__공백을 그대로 보존한다`() {
        // given
        val readPost = createReader(userNicknameQuery { Optional.of("  \t ") })

        // when
        val details = readPost.read(42L)

        // then
        assertEquals("  \t ", details.authorNickname)
    }

    @Test
    fun `게시글이 없을 때__조회하면__찾을 수 없음 예외를 던지고 닉네임을 조회하지 않는다`() {
        // given
        val readPost = ReadPost(
            postReader { Optional.empty() },
            userNicknameQuery { throw AssertionError("Nickname lookup must follow a successful post lookup") },
        )

        // when
        val thrown = assertThrows(PostNotFoundException::class.java) { readPost.read(-1L) }

        // then
        assertEquals(PostNotFoundException::class.java, thrown.javaClass)
    }

    @Test
    fun `게시글 조회가 실패할 때__조회하면__닉네임 조회 전에 예외를 그대로 전파한다`() {
        // given
        val failure = IllegalStateException("post mapping failed")
        val readPost = ReadPost(
            postReader { throw failure },
            userNicknameQuery { throw AssertionError("Nickname lookup must follow a successful post lookup") },
        )

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { readPost.read(42L) }

        // then
        assertSame(failure, thrown)
    }

    @Test
    fun `닉네임 조회가 예상 밖으로 실패할 때__조회하면__대체 이름으로 가리지 않고 예외를 전파한다`() {
        // given
        val failure = IllegalArgumentException("programming failure")
        val readPost = createReader(userNicknameQuery { throw failure })

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { readPost.read(42L) }

        // then
        assertSame(failure, thrown)
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
