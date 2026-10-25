package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.util.Optional

class UpdatePostTest {
    private val reader = mock(PostReader::class.java)
    private val remover = mock(PostMediaRemover::class.java)
    private val uploader = mock(PostMediaUploader::class.java)
    private val users = mock(UserNicknameQuery::class.java)
    private val writer = mock(PostModification::class.java)
    private val update = UpdatePost(reader, remover, uploader, users, writer)
    private val time = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val previous = PostSnapshot(42, 7, "제목", "본문", "MISSING", listOf("https://fixture.invalid/old"), time, time)

    @BeforeEach
    fun givenExistingOwner() {
        whenever(reader.findById(42)).thenReturn(Optional.of(previous))
        whenever(users.findNickname(7)).thenReturn(Optional.of("작성자"))
    }

    @Test
    fun given__another_authors_post__when__updating_as_admin__then__retain_its_author_and_reuse_the_same_writer_without_media() {
        whenever(writer.update(42, 7, "admin edit", null, null)).thenReturn(previous)

        val result = update.updateAsAdmin(42, "admin edit", null, listOf())

        assertThat(result.authorId).isEqualTo(7)
        assertThat(result.authorNickname).isEqualTo("작성자")
        val order = inOrder(reader, users, writer)
        order.verify(reader).findById(42)
        order.verify(users).findNickname(7)
        order.verify(writer).update(42, 7, "admin edit", null, null)
        order.verifyNoMoreInteractions()
        verifyNoInteractions(remover, uploader)
    }

    @Test
    fun given__omitted_files_and_null_fields__when__updating__then__pass_null_patch_without_media_and_keep_missing_board() {
        whenever(writer.update(42, 7, null, null, null)).thenReturn(previous)

        val response = update.update(42, 7, null, null, listOf())

        assertThat(response.authorNickname).isEqualTo("작성자")
        assertThat(response.boardType).isEqualTo("MISSING")
        verifyNoInteractions(remover, uploader)
        verify(writer).update(42, 7, null, null, null)
    }

    @Test
    fun given__one_empty_file_and_explicit_empty_text__when__updating__then__remove_upload_lookup_and_write_in_order() {
        val file = PostUpload("empty.png", "image/png", 0, PostUpload.Content { ByteArrayInputStream(ByteArray(0)) })
        whenever(uploader.upload(listOf(file))).thenReturn(listOf())
        whenever(writer.update(42, 7, "", "", listOf()))
            .thenReturn(PostSnapshot(42, 7, "", "", "MISSING", listOf(), time, time))

        assertThat(update.update(42, 7, "", "", listOf(file)).imageUrls).isEmpty()

        val order = inOrder(reader, remover, uploader, users, writer)
        order.verify(reader).findById(42)
        order.verify(remover).remove(previous.imageUrls())
        order.verify(uploader).upload(listOf(file))
        order.verify(users).findNickname(7)
        order.verify(writer).update(42, 7, "", "", listOf())
    }

    @Test
    fun given__foreign_author__when__updating__then__deny_before_any_external_or_writer_work() {
        assertThatThrownBy { update.update(42, 8, "수정", null, listOf()) }
            .isInstanceOf(PostNotOwnedException::class.java)
        verifyNoInteractions(remover, uploader, users, writer)
    }

    @Test
    fun given__missing_post__when__updating__then__404_before_any_external_or_writer_work() {
        whenever(reader.findById(42)).thenReturn(Optional.empty())

        assertThatThrownBy { update.update(42, 7, "수정", null, listOf()) }
            .isInstanceOf(PostNotFoundException::class.java)
        verifyNoInteractions(remover, uploader, users, writer)
    }

    @Test
    fun given__upload_failure_after_old_media_attempt__when__updating__then__propagate_without_writer_or_compensation() {
        val file = PostUpload("a.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val failure = IllegalStateException("synthetic upload failure")
        whenever(uploader.upload(listOf(file))).thenThrow(failure)

        assertThatThrownBy { update.update(42, 7, null, null, listOf(file)) }.isSameAs(failure)
        verify(remover).remove(previous.imageUrls())
        verifyNoInteractions(users, writer)
        verifyNoMoreInteractions(remover)
    }

    @Test
    fun given__late_writer_rejection_after_upload__when__updating__then__propagate_without_retry_or_media_compensation() {
        val file = PostUpload("a.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        whenever(uploader.upload(listOf(file))).thenReturn(listOf("https://fixture.invalid/new"))
        val failure = PostNotFoundException()
        whenever(writer.update(42, 7, null, null, listOf("https://fixture.invalid/new"))).thenThrow(failure)

        assertThatThrownBy { update.update(42, 7, null, null, listOf(file)) }.isSameAs(failure)
        verify(writer).update(42, 7, null, null, listOf("https://fixture.invalid/new"))
        verify(remover).remove(previous.imageUrls())
        verifyNoMoreInteractions(remover, writer)
    }

    @Test
    fun given__missing_nickname__when__updating__then__use_legacy_fallback_after_media_before_writer() {
        whenever(users.findNickname(7)).thenReturn(Optional.empty())
        whenever(writer.update(42, 7, null, null, null)).thenReturn(previous)

        assertThat(update.update(42, 7, null, null, listOf()).authorNickname).isEqualTo("사용자7")
    }

    @Test
    fun given__empty_nickname__when__updating__then__preserve_legacy_null_nickname() {
        whenever(users.findNickname(7)).thenReturn(Optional.of(""))
        whenever(writer.update(42, 7, null, null, null)).thenReturn(previous)

        assertThat(update.update(42, 7, null, null, listOf()).authorNickname).isNull()
    }

    @Test
    fun given__nickname_transport_unavailable__when__updating__then__use_legacy_fallback() {
        whenever(users.findNickname(7))
            .thenThrow(UserNicknameUnavailableException(IllegalStateException("synthetic")))
        whenever(writer.update(42, 7, null, null, null)).thenReturn(previous)

        assertThat(update.update(42, 7, null, null, listOf()).authorNickname).isEqualTo("사용자7")
    }
}
