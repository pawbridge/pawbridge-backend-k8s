package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.support.postMediaUploader
import com.pawbridge.backend.community.support.postWriter
import com.pawbridge.backend.community.support.userNicknameQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.Optional

class PostCreateControllerTest {
    @Test
    fun given__multipart_media__when__controller_maps_uploads__then__stream_is_opened_lazily_by_uploader() {
        val file = mock(MultipartFile::class.java)
        val bytes = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        whenever(file.originalFilename).thenReturn("photo.png")
        whenever(file.contentType).thenReturn("image/png")
        whenever(file.size).thenReturn(3L)
        whenever(file.inputStream).thenReturn(bytes)
        val useCase = CreatePost(
            postMediaUploader { files ->
                assertEquals(1, files!!.size)
                assertEquals("photo.png", files.first().originalFilename)
                assertEquals("image/png", files.first().contentType)
                assertEquals(3L, files.first().size)
                try {
                    verify(file, never()).inputStream
                    assertSame(bytes, files.first().content.open())
                } catch (failure: IOException) {
                    throw AssertionError(failure)
                }
                listOf("https://example.test/photo.png")
            },
            userNicknameQuery { Optional.of("합성 회원") },
            postWriter { post ->
                assertEquals(7L, post.authorId())
                PostSnapshot(
                    42L,
                    post.authorId(),
                    post.title(),
                    post.content(),
                    post.boardType().name,
                    post.imageUrls(),
                    LocalDateTime.of(2026, 10, 18, 12, 0),
                    LocalDateTime.of(2026, 10, 18, 12, 0),
                )
            },
        )

        val response = PostCreateController(useCase).create(
            "제목",
            "본문",
            PostBoardType.PROTECTION,
            arrayOf(file),
            7L,
        )

        assertEquals(200, response.code)
        assertEquals("게시글이 생성되었습니다.", response.message)
        verify(file).inputStream
    }

    @Test
    fun given__forbidden_board_with_file__when__create__then__file_stream_is_never_opened() {
        val file = mock(MultipartFile::class.java)
        val useCase = CreatePost(
            postMediaUploader { throw AssertionError("No upload") },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No write") },
        )

        assertThrows(PostBoardNotAllowedException::class.java) {
            PostCreateController(useCase).create("제목", "본문", PostBoardType.MISSING, arrayOf(file), 7L)
        }

        verify(file, never()).inputStream
    }
}
