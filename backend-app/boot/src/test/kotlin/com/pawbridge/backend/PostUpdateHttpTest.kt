package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional

@WebMvcTest(
    controllers = [PostUpdateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostUpdateController::class,
        PostUpdateExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        PostUpdateHttpTest.UseCase::class,
    ],
)
class PostUpdateHttpTest {
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

    private val time = LocalDateTime.of(2026, 1, 1, 0, 0)

    @TestConfiguration(proxyBeanMethods = false)
    class UseCase {
        @Bean
        fun updatePost(
            reader: PostReader,
            remover: PostMediaRemover,
            uploader: PostMediaUploader,
            users: UserNicknameQuery,
            writer: PostModification,
        ): UpdatePost {
            return UpdatePost(reader, remover, uploader, users, writer)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `검증된 작성자와 위조 헤더가 주어졌을 때__멀티파트 PUT을 보내면__원래 아홉 필드와 검증된 작성자를 돌려준다`(
        role: String,
    ) {
        // given
        val existing = snapshot(7, "COMMUNICATION")

        every { reader.findById(any()) } returns Optional.of(existing)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            modified(existing, arg(2), arg(3), arg(4))
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .param("title", "수정 제목")
                .param("content", "")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.title").value("수정 제목"))
            .andExpect(jsonPath("$.data.content").value(""))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { writer.update(42, 7, "수정 제목", "", null) }
        confirmVerified(remover, uploader)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "PROTECTION", "REPORT", "ADOPTION", "COMMUNICATION"])
    fun `기존 게시판이 주어졌을 때__필드 없이 PUT하면__일반 등록 제한을 적용하지 않는다`(
        board: String,
    ) {
        // given
        val existing = snapshot(7, board)

        every { reader.findById(any()) } returns Optional.of(existing)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            modified(existing, arg(2), arg(3), arg(4))
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.boardType").value(board))
            .andExpect(jsonPath("$.data.title").value("제목"))
        verify(exactly = 1) { writer.update(42, 7, null, null, null) }
        confirmVerified(remover, uploader)
    }

    @Test
    fun `빈 멀티파트 파일 하나가 주어졌을 때__PUT하면__빈 결과로 미디어 교체를 요청한다`() {
        // given
        val existing = snapshot(7, "COMMUNICATION")

        every { reader.findById(any()) } returns Optional.of(existing)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } returns listOf()
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            modified(existing, arg(2), arg(3), arg(4))
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .file(MockMultipartFile("files", "empty.png", "image/png", ByteArray(0)))
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.imageUrls").isEmpty())
        verify(exactly = 1) { remover.remove(snapshot(7, "COMMUNICATION").imageUrls()) }
        verify(exactly = 1) {
            uploader.upload(match<List<PostUpload>> { files -> files.size == 1 && files.first().size == 0L })
        }
        verify(exactly = 1) { writer.update(42, 7, null, null, listOf()) }
    }

    @Test
    fun `웹 검증 없는 긴 제목이 주어졌을 때__PUT하면__400을 더하지 않고 작성기에 전달한다`() {
        // given
        val title = "x".repeat(201)
        val existing = snapshot(7, "COMMUNICATION")

        every { reader.findById(any()) } returns Optional.of(existing)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } answers {
            modified(existing, arg(2), arg(3), arg(4))
        }

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .param("title", title)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { writer.update(42, 7, title, null, null) }
    }

    @Test
    fun `다른 사람의 글에 관리자가 주어졌을 때__PUT하면__미디어와 닉네임과 작성기 전에 403이다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(snapshot(7, "COMMUNICATION"))

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(8, "ROLE_ADMIN")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("게시글에 접근할 권한이 없습니다."))
        confirmVerified(remover, uploader, users, writer)
    }

    @Test
    fun `없는 게시글이 주어졌을 때__PUT하면__미디어와 닉네임과 작성기 전에 404이다`() {
        // given
        every { reader.findById(any()) } returns Optional.empty()

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
        confirmVerified(remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "refresh", "expired"])
    fun `검증되지 않은 토큰이 주어졌을 때__PUT하면__모든 업무 포트 전에 401이다`(token: String) {
        // given
        val request = multipart(HttpMethod.PUT, "/api/v1/posts/42").header("X-User-Id", "7")
        val value: String? = when (token) {
            "invalid" -> "invalid"
            "refresh" -> Jwts.builder()
                .subject("7")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            "expired" -> Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() - 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            else -> null
        }
        if (value != null) {
            request.header("Authorization", "Bearer $value")
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
        confirmVerified(reader, remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `잘못된 게시글 ID가 주어졌을 때__PUT하면__기존 500을 유지한다`(id: String) {
        // given
        val request = multipart(HttpMethod.PUT, "/api/v1/posts/$id")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        confirmVerified(reader, remover, uploader, users, writer)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "PUT /api/v1/posts", "PUT /api/v1/posts/42/extra", "PUT /api/v1/posts/read/42",
            "PUT /api/v1/admin/posts/42/extra", "PATCH /api/v1/posts/42", "POST /api/v1/posts/42",
            "GET /api/v1/posts/42",
        ],
    )
    fun `노출되지 않은 경로나 메서드가 주어졌을 때__요청하면__업무 전에 거부한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(reader, remover, uploader, users, writer)
    }

    @Test
    fun `인증 뒤 작성기 실패가 주어졌을 때__PUT하면__안전한 500을 돌려주고 주체를 비운다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(snapshot(7, "COMMUNICATION"))
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every {
            writer.update(any(), any(), any(), any(), any())
        } throws IllegalStateException("synthetic-private-db-detail")

        // when
        val result = mvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(Matchers.not(Matchers.containsString("synthetic-private-db-detail"))))
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun snapshot(author: Long, board: String): PostSnapshot {
        return PostSnapshot(42, author, "제목", "본문", board, listOf("https://fixture.invalid/old"), time, time)
    }

    private fun modified(
        old: PostSnapshot,
        title: String?,
        content: String?,
        images: List<String?>?,
    ): PostSnapshot {
        return PostSnapshot(
            old.postId(),
            old.authorId(),
            title ?: old.title(),
            content ?: old.content(),
            old.boardType(),
            images ?: old.imageUrls(),
            time,
            time,
        )
    }
}
