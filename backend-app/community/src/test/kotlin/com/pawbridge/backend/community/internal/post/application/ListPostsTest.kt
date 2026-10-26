package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class ListPostsTest {
    private val postListReader = mockk<PostListReader>()
    private val nicknameQuery = mockk<UserNicknameQuery>()
    private val listPosts = ListPosts(postListReader, nicknameQuery)

    @Test
    fun `게시글이 없을 때__조회하면__닉네임 조회 없이 빈 목록을 돌려준다`() {
        // given
        every { postListReader.findAll() } returns listOf()

        // when
        val result = listPosts.read()

        // then
        assertEquals(listOf<PostDetails>(), result)
        confirmVerified(nicknameQuery)
    }

    @Test
    fun `같은 작성자의 글이 섞여 정렬되어 있을 때__조회하면__각 행을 순서대로 닉네임으로 채운다`() {
        // given
        val first = post(42L, 7L)
        val second = post(41L, 9L)
        val third = post(40L, 7L)

        every { postListReader.findAll() } returns listOf(first, second, third)
        every { nicknameQuery.findNickname(7L) } returnsMany listOf(
            Optional.of("첫 닉네임"),
            Optional.of("다음 닉네임"),
        )
        every { nicknameQuery.findNickname(9L) } returns Optional.of("다른 작성자")

        // when
        val result = listPosts.read()

        // then
        assertEquals(
            listOf(details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
            result,
        )
        verifySequence {
            postListReader.findAll()
            nicknameQuery.findNickname(7L)
            nicknameQuery.findNickname(9L)
            nicknameQuery.findNickname(7L)
        }
    }

    @Test
    fun `닉네임이 없거나 조회할 수 없을 때__조회하면__영향받은 행만 대체 이름을 쓴다`() {
        // given
        val missing = post(42L, 7L)
        val unavailable = post(41L, 8L)
        val available = post(40L, 7L)

        every { postListReader.findAll() } returns listOf(missing, unavailable, available)
        every { nicknameQuery.findNickname(7L) } returnsMany listOf(
            Optional.empty(),
            Optional.of("회복한 닉네임"),
        )
        every { nicknameQuery.findNickname(8L) } throws
            UserNicknameUnavailableException(IllegalStateException("unavailable"))

        // when
        val result = listPosts.read()

        // then
        assertEquals(
            listOf(details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
            result,
        )
    }

    @Test
    fun `닉네임이 빈 문자열이거나 공백일 때__조회하면__빈 문자열은 null로 공백은 그대로 둔다`() {
        // given
        val empty = post(42L, 7L)
        val whitespace = post(41L, 8L)

        every { postListReader.findAll() } returns listOf(empty, whitespace)
        every { nicknameQuery.findNickname(7L) } returns Optional.of("")
        every { nicknameQuery.findNickname(8L) } returns Optional.of("  \t ")

        // when
        val result = listPosts.read()

        // then
        assertEquals(listOf(details(empty, null), details(whitespace, "  \t ")), result)
    }

    @Test
    fun `게시글 조회가 실패할 때__조회하면__원래 예외를 던지고 닉네임을 조회하지 않는다`() {
        // given
        val failure = IllegalStateException("post mapping failed")

        every { postListReader.findAll() } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { listPosts.read() }

        // then
        assertSame(failure, thrown)
        confirmVerified(nicknameQuery)
    }

    @Test
    fun `닉네임 조회가 예상 밖으로 실패할 때__조회하면__목록 전체가 실패하고 뒤 행은 조회하지 않는다`() {
        // given
        val failure = IllegalArgumentException("programming failure")

        every { postListReader.findAll() } returns listOf(post(42L, 7L), post(41L, 8L), post(40L, 9L))
        every { nicknameQuery.findNickname(7L) } returns Optional.of("정상")
        every { nicknameQuery.findNickname(8L) } throws failure

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { listPosts.read() }

        // then
        assertSame(failure, thrown)
        verifySequence {
            nicknameQuery.findNickname(7L)
            nicknameQuery.findNickname(8L)
        }
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
