package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.support.postMediaUploader
import com.pawbridge.backend.community.support.postWriter
import com.pawbridge.backend.community.support.userNicknameQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.Optional

class PostCreateControllerTest {
    @Test
    fun `멀티파트 미디어가 있을 때__컨트롤러가 업로드로 변환하면__스트림은 업로더가 필요할 때 열린다`() {
        // given
        val file = mockk<MultipartFile>()
        val bytes = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        every { file.originalFilename } returns "photo.png"
        every { file.contentType } returns "image/png"
        every { file.size } returns 3L
        every { file.inputStream } returns bytes

        val useCase = CreatePost(
            postMediaUploader { files ->
                assertEquals(1, files!!.size)
                assertEquals("photo.png", files.first().originalFilename)
                assertEquals("image/png", files.first().contentType)
                assertEquals(3L, files.first().size)
                try {
                    verify(exactly = 0) { file.inputStream }
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

        // when
        val response = PostCreateController(useCase).create(
            "제목",
            "본문",
            PostBoardType.PROTECTION,
            arrayOf(file),
            7L,
        )

        // then
        assertEquals(200, response.code)
        assertEquals("게시글이 생성되었습니다.", response.message)
        verify(exactly = 1) { file.inputStream }
    }

    @Test
    fun `금지된 게시판에 파일이 있을 때__생성하면__파일 스트림을 열지 않는다`() {
        // given
        val file = mockk<MultipartFile>()

        every { file.originalFilename } returns "photo.png"
        every { file.contentType } returns "image/png"
        every { file.size } returns 3L

        val useCase = CreatePost(
            postMediaUploader { throw AssertionError("No upload") },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No write") },
        )

        // when
        val thrown = assertThrows(PostBoardNotAllowedException::class.java) {
            PostCreateController(useCase).create("제목", "본문", PostBoardType.MISSING, arrayOf(file), 7L)
        }

        // then
        assertEquals(PostBoardNotAllowedException::class.java, thrown.javaClass)
        verify(exactly = 0) { file.inputStream }
    }
}
