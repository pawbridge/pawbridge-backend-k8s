package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDateTime
import java.util.Optional

class DeletePostTest {
    private val reader = mockk<PostReader>()
    private val media = mockk<PostMediaRemover>()
    private val deletion = mockk<PostDeletion>()
    private val useCase = DeletePost(reader, media, deletion)

    @Test
    fun `다른 작성자의 게시글일 때__관리자로 삭제하면__같은 잠금 변경 안에서 원작성자를 유지한다`() {
        // given
        val post = post(8L, "COMMUNICATION", listOf("first", "first"))

        every { reader.findById(any()) } returns Optional.of(post)
        justRun { media.remove(any()) }
        justRun { deletion.delete(any(), any()) }

        // when
        useCase.deleteAsAdmin(42L)

        // then
        verifySequence {
            reader.findById(42L)
            media.remove(post.imageUrls())
            deletion.delete(42L, 8L)
        }
    }

    @ParameterizedTest
    @EnumSource(PostBoardType::class)
    fun `어느 게시판이든 본인의 활성 게시글일 때__삭제하면__조회와 미디어 삭제와 저장을 한 번씩 순서대로 한다`(
        board: PostBoardType,
    ) {
        // given
        val post = post(7L, board.name, listOf("https://fixture.invalid/posts/images/one.png"))

        every { reader.findById(any()) } returns Optional.of(post)
        justRun { media.remove(any()) }
        justRun { deletion.delete(any(), any()) }

        // when
        useCase.delete(42L, 7L)

        // then
        verifySequence {
            reader.findById(42L)
            media.remove(post.imageUrls())
            deletion.delete(42L, 7L)
        }
    }

    @Test
    fun `게시글이 없거나 이미 삭제됐을 때__삭제하면__어떤 부수 효과보다 먼저 찾을 수 없음 예외를 던진다`() {
        // given
        every { reader.findById(any()) } returns Optional.empty()

        // when
        val thrown = assertThrows(PostNotFoundException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertEquals(PostNotFoundException::class.java, thrown.javaClass)
        confirmVerified(media, deletion)
    }

    @Test
    fun `작성자가 다를 때__삭제하면__어떤 부수 효과보다 먼저 고정된 금지 예외를 던진다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(post(8L, "COMMUNICATION", listOf()))

        // when
        val failure = assertThrows(PostNotOwnedException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertEquals("게시글에 접근할 권한이 없습니다.", failure.message)
        confirmVerified(media, deletion)
    }

    @Test
    fun `조회가 실패할 때__삭제하면__어떤 부수 효과보다 먼저 원래 예외를 던진다`() {
        // given
        val failure = IllegalStateException("synthetic reader failure")

        every { reader.findById(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        confirmVerified(media, deletion)
    }

    @Test
    fun `미디어 목록이 null이거나 중복일 때__삭제하면__정규화 없이 목록을 그대로 넘긴다`() {
        // given
        val mediaLists: List<List<String?>?> = listOf(null, listOf(), listOf("first", null, "first", "last"))

        for (urls in mediaLists) {
            clearMocks(reader, media, deletion)
            val post = post(7L, "MISSING", urls)

            every { reader.findById(any()) } returns Optional.of(post)
            justRun { media.remove(any()) }
            justRun { deletion.delete(any(), any()) }

            // when
            useCase.delete(42L, 7L)

            // then
            verify(exactly = 1) { media.remove(post.imageUrls()) }
            verify(exactly = 1) { deletion.delete(42L, 7L) }
        }
    }

    @Test
    fun `미디어 삭제 뒤 저장에서 늦게 찾을 수 없을 때__삭제하면__재시도나 보상 없이 예외를 전파한다`() {
        // given
        val failure = PostNotFoundException()

        every { reader.findById(any()) } returns Optional.of(post(7L, "REPORT", listOf("first")))
        justRun { media.remove(any()) }
        every { deletion.delete(any(), any()) } throws failure

        // when
        val thrown = assertThrows(PostNotFoundException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { media.remove(listOf("first")) }
        verify(exactly = 1) { deletion.delete(42L, 7L) }
        confirmVerified(media, deletion)
    }

    @Test
    fun `저장에서 소유권이 늦게 거부될 때__삭제하면__앞서 시도한 미디어 삭제를 반복하지 않는다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(post(7L, "ADOPTION", listOf("first")))
        justRun { media.remove(any()) }
        every { deletion.delete(any(), any()) } throws PostNotOwnedException()

        // when
        val thrown = assertThrows(PostNotOwnedException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertEquals(PostNotOwnedException::class.java, thrown.javaClass)
        verify(exactly = 1) { media.remove(listOf("first")) }
        verify(exactly = 1) { deletion.delete(42L, 7L) }
        confirmVerified(media, deletion)
    }

    @Test
    fun `커밋 결과를 알 수 없을 때__삭제하면__원래 예외를 던지고 애플리케이션이 재시도하지 않는다`() {
        // given
        val failure = IllegalStateException("synthetic commit outcome unknown")

        every { reader.findById(any()) } returns Optional.of(post(7L, "PROTECTION", listOf("first")))
        justRun { media.remove(any()) }
        every { deletion.delete(any(), any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { useCase.delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { reader.findById(42L) }
        verify(exactly = 1) { media.remove(listOf("first")) }
        verify(exactly = 1) { deletion.delete(42L, 7L) }
        confirmVerified(reader, media, deletion)
    }

    private fun post(author: Long, board: String, urls: List<String?>?): PostSnapshot {
        val time = LocalDateTime.of(2026, 10, 19, 9, 0)
        return PostSnapshot(42L, author, "preserved title", "preserved content", board, urls, time, time)
    }
}
