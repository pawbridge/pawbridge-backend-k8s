package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Optional

@WebMvcTest(
    controllers = [AdminPostUpdateController::class, AdminPostDeleteController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminPostUpdateController::class,
        AdminPostDeleteController::class,
        PostUpdateExceptionHandler::class,
        PostDeleteExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        AdminPostMutationHttpTest.UseCases::class,
    ],
)
class AdminPostMutationHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var reader: PostReader

    @MockkBean
    lateinit var remover: PostMediaRemover

    @MockkBean
    lateinit var uploader: PostMediaUploader

    @MockkBean
    lateinit var users: UserNicknameQuery

    @MockkBean
    lateinit var writer: PostModification

    @MockkBean
    lateinit var deletion: PostDeletion

    private val time = LocalDateTime.of(2026, 10, 20, 12, 0)
    private lateinit var existing: PostSnapshot

    @TestConfiguration(proxyBeanMethods = false)
    class UseCases {
        @Bean
        fun update(
            reader: PostReader,
            remover: PostMediaRemover,
            uploader: PostMediaUploader,
            users: UserNicknameQuery,
            writer: PostModification,
        ): UpdatePost {
            return UpdatePost(reader, remover, uploader, users, writer)
        }

        @Bean
        fun delete(reader: PostReader, remover: PostMediaRemover, deletion: PostDeletion): DeletePost {
            return DeletePost(reader, remover, deletion)
        }
    }

    @BeforeEach
    fun prepareAnotherAuthorsExistingPost() {
        existing = PostSnapshot(42, 7, "title", "content", "MISSING", listOf("https://fixture.invalid/old"), time, time)
    }

    @Test
    fun `검증된 관리자와 타인 작성자 글__부분 수정하면__원작성자와 아홉 필드를 유지하고 위조 신원을 무시한다`() {
        // given
        every { reader.findById(42) } returns Optional.of(existing)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            PostSnapshot(
                42,
                7,
                arg<String?>(2) ?: existing.title(),
                arg<String?>(3) ?: existing.content(),
                existing.boardType(),
                arg<List<String?>?>(4) ?: existing.imageUrls(),
                time,
                time,
            )
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .param("title", "")
                .param("authorId", "99")
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_USER")
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.title").value(""))
            .andExpect(jsonPath("$.data.content").value("content"))
            .andExpect(jsonPath("$.data.boardType").value("MISSING"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { writer.update(42, 7, "", null, null) }
        verify(exactly = 1) { users.findNickname(7) }
        confirmVerified(remover, uploader, deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `빈 파일 하나__관리자가 수정하면__기존 순서대로 미디어를 빈 목록으로 교체한다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(existing)
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } returns listOf()
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            PostSnapshot(
                42,
                7,
                arg<String?>(2) ?: existing.title(),
                arg<String?>(3) ?: existing.content(),
                existing.boardType(),
                arg<List<String?>?>(4) ?: existing.imageUrls(),
                time,
                time,
            )
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .file(MockMultipartFile("files", "empty.png", "image/png", ByteArray(0)))
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.imageUrls").isEmpty())
        verifyOrder {
            reader.findById(42)
            remover.remove(existing.imageUrls())
            uploader.upload(match { files -> files != null && files.size == 1 && files.first().size == 0L })
            users.findNickname(7)
            writer.update(42, 7, null, null, listOf())
        }
    }

    @Test
    fun `이전 미디어 삭제 시도 뒤 업로드 실패__관리자가 수정하면__DB 접근과 보상 없이 안전한 500을 돌려준다`() {
        // given
        every { reader.findById(42) } returns Optional.of(existing)
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } throws IllegalStateException(PRIVATE)

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "$ROOT/42")
                .file(MockMultipartFile("files", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(content().string(Matchers.not(Matchers.containsString(PRIVATE))))
        verify(exactly = 1) { remover.remove(existing.imageUrls()) }
        confirmVerified(remover)
        confirmVerified(users, writer, deletion)
    }

    @Test
    fun `타인 글을 삭제한 뒤 이미 삭제됨__관리자가 두 번 삭제하면__첫 번째는 빈 데이터 두 번째는 404이고 효과는 반복되지 않는다`() {
        // given
        every { reader.findById(42) } returns Optional.of(existing)
        justRun { remover.remove(any()) }
        justRun { deletion.delete(any(), any()) }

        // when
        val first = mvc.perform(delete("$ROOT/42").header("Authorization", admin()))
        every { reader.findById(42) } returns Optional.empty()
        val second = mvc.perform(delete("$ROOT/42").header("Authorization", admin()))

        // then
        first
            .andExpect(status().isOk())
            .andExpect(content().json("{\"code\":200,\"message\":\"게시글이 삭제되었습니다.\",\"data\":null}"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        second.andExpect(status().isNotFound())
        verifyOrder {
            remover.remove(existing.imageUrls())
            deletion.delete(42, 7)
        }
        confirmVerified(remover, deletion)
        confirmVerified(uploader, users, writer)
    }

    @ParameterizedTest
    @CsvSource("PUT,missing,404", "DELETE,missing,404", "PUT,db,500", "DELETE,db,500")
    fun `없는 글 또는 늦은 쓰기 실패__관리자가 변경하면__범위 응답과 안전한 메타데이터만 로그한다`(
        method: String,
        mode: String,
        expected: Int,
    ) {
        // given
        if (mode == "missing") {
            every { reader.findById(42) } returns Optional.empty()
        } else if (method == "PUT") {
            every { reader.findById(42) } returns Optional.of(existing)
            every { users.findNickname(7) } returns Optional.of("작성자")
            every { writer.update(42, 7, null, null, null) } throws IllegalStateException(PRIVATE)
        } else {
            every { reader.findById(42) } returns Optional.of(existing)
            justRun { remover.remove(any()) }
            every { deletion.delete(42, 7) } throws IllegalStateException(PRIVATE)
        }
        val logger = LoggerFactory.getLogger(
            if (method == "PUT") PostUpdateExceptionHandler::class.java else PostDeleteExceptionHandler::class.java,
        ) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)

        try {
            // when
            val result = mvc.perform(mutation(method, "42").header("Authorization", admin()))

            // then
            result
                .andExpect(status().`is`(expected))
                .andExpect(jsonPath("$.message").value(if (expected == 404) "게시글을 찾을 수 없습니다." else "서버 에러!"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(PRIVATE))))
            assertThat(events.list).allSatisfy { event ->
                assertThat(event.formattedMessage).doesNotContain(PRIVATE)
                assertThat(event.throwableProxy).isNull()
            }
            if (expected == 500) {
                assertThat(events.list).anySatisfy { event ->
                    assertThat(event.formattedMessage).contains("java.lang.IllegalStateException")
                }
            } else {
                confirmVerified(remover, uploader, users, writer, deletion)
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    companion object {
        private const val ROOT = "/api/v1/admin/posts"
        private const val PRIVATE = "synthetic-private-upload-or-db-value"

        private fun mutation(method: String, id: String): MockHttpServletRequestBuilder {
            return if (method == "PUT") multipart(HttpMethod.PUT, "$ROOT/$id") else delete("$ROOT/$id")
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(99, "ROLE_ADMIN")
    }
}
