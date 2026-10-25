package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.LocalDateTime
import java.util.Optional

class DeletePostTest {
    private val reader = mock(PostReader::class.java)
    private val media = mock(PostMediaRemover::class.java)
    private val deletion = mock(PostDeletion::class.java)
    private val useCase = DeletePost(reader, media, deletion)

    @Test
    fun given__another_authors_post__when__deleting_as_admin__then__retain_its_author_in_the_same_locking_mutation() {
        val post = post(8L, "COMMUNICATION", listOf("first", "first"))
        whenever(reader.findById(42L)).thenReturn(Optional.of(post))

        useCase.deleteAsAdmin(42L)

        val order = inOrder(reader, media, deletion)
        order.verify(reader).findById(42L)
        order.verify(media).remove(post.imageUrls())
        order.verify(deletion).delete(42L, 8L)
        order.verifyNoMoreInteractions()
    }

    @ParameterizedTest
    @EnumSource(PostBoardType::class)
    fun given__own_active_post_of_any_board__when__delete__then__read_media_and_write_occur_once_in_order(
        board: PostBoardType,
    ) {
        val post = post(7L, board.name, listOf("https://fixture.invalid/posts/images/one.png"))
        whenever(reader.findById(42L)).thenReturn(Optional.of(post))

        useCase.delete(42L, 7L)

        val order = inOrder(reader, media, deletion)
        order.verify(reader).findById(42L)
        order.verify(media).remove(post.imageUrls())
        order.verify(deletion).delete(42L, 7L)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__missing_or_already_deleted_post__when__delete__then__not_found_precedes_any_effect() {
        whenever(reader.findById(42L)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) { useCase.delete(42L, 7L) }

        verifyNoInteractions(media, deletion)
    }

    @Test
    fun given__another_author__when__delete__then__fixed_forbidden_precedes_any_effect() {
        whenever(reader.findById(42L)).thenReturn(Optional.of(post(8L, "COMMUNICATION", listOf())))

        val failure = assertThrows(PostNotOwnedException::class.java) { useCase.delete(42L, 7L) }

        assertEquals("게시글에 접근할 권한이 없습니다.", failure.message)
        verifyNoInteractions(media, deletion)
    }

    @Test
    fun given__reader_failure__when__delete__then__original_failure_precedes_any_effect() {
        val failure = IllegalStateException("synthetic reader failure")
        whenever(reader.findById(42L)).thenThrow(failure)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { useCase.delete(42L, 7L) })

        verifyNoInteractions(media, deletion)
    }

    @Test
    fun given__null_or_duplicate_media__when__delete__then__list_is_passed_without_normalization() {
        val mediaLists: List<List<String?>?> = listOf(null, listOf(), listOf("first", null, "first", "last"))
        for (urls in mediaLists) {
            reset(reader, media, deletion)
            val post = post(7L, "MISSING", urls)
            whenever(reader.findById(42L)).thenReturn(Optional.of(post))

            useCase.delete(42L, 7L)

            verify(media).remove(post.imageUrls())
            verify(deletion).delete(42L, 7L)
        }
    }

    @Test
    fun given__late_not_found_after_media__when__delete__then__failure_propagates_without_retry_or_compensation() {
        whenever(reader.findById(42L)).thenReturn(Optional.of(post(7L, "REPORT", listOf("first"))))
        val failure = PostNotFoundException()
        doThrow(failure).`when`(deletion).delete(42L, 7L)

        assertSame(failure, assertThrows(PostNotFoundException::class.java) { useCase.delete(42L, 7L) })

        verify(media).remove(listOf("first"))
        verify(deletion).delete(42L, 7L)
        verifyNoMoreInteractions(media, deletion)
    }

    @Test
    fun given__late_ownership_rejection__when__delete__then__prior_media_attempt_is_not_repeated() {
        whenever(reader.findById(42L)).thenReturn(Optional.of(post(7L, "ADOPTION", listOf("first"))))
        doThrow(PostNotOwnedException()).`when`(deletion).delete(42L, 7L)

        assertThrows(PostNotOwnedException::class.java) { useCase.delete(42L, 7L) }

        verify(media).remove(listOf("first"))
        verify(deletion).delete(42L, 7L)
        verifyNoMoreInteractions(media, deletion)
    }

    @Test
    fun given__commit_outcome_unknown__when__delete__then__original_failure_and_no_application_retry() {
        whenever(reader.findById(42L)).thenReturn(Optional.of(post(7L, "PROTECTION", listOf("first"))))
        val failure = IllegalStateException("synthetic commit outcome unknown")
        doThrow(failure).`when`(deletion).delete(42L, 7L)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { useCase.delete(42L, 7L) })

        verify(reader).findById(42L)
        verify(media).remove(listOf("first"))
        verify(deletion).delete(42L, 7L)
        verifyNoMoreInteractions(reader, media, deletion)
    }

    private fun post(author: Long, board: String, urls: List<String?>?): PostSnapshot {
        val time = LocalDateTime.of(2026, 10, 19, 9, 0)
        return PostSnapshot(42L, author, "preserved title", "preserved content", board, urls, time, time)
    }
}
