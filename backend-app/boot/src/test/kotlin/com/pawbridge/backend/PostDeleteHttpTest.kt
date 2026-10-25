package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional
import java.util.stream.Stream

@WebMvcTest(
    controllers = [PostDeleteController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostDeleteController::class,
        PostDeleteExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        PostDeleteHttpTest.UseCase::class,
    ],
)
class PostDeleteHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var posts: PostReader

    @MockkBean
    lateinit var media: PostMediaRemover

    @MockkBean
    lateinit var deletion: PostDeletion

    @TestConfiguration(proxyBeanMethods = false)
    class UseCase {
        @Bean
        fun deletePost(posts: PostReader, media: PostMediaRemover, deletion: PostDeletion): DeletePost {
            return DeletePost(posts, media, deletion)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `소유자와 위조 신원 헤더가 주어졌을 때__삭제하면__검증된 신원과 기존 응답 형식을 쓴다`(
        role: String,
    ) {
        // given
        every { posts.findById(any()) } returns Optional.of(snapshot(7))
        justRun { media.remove(any()) }
        justRun { deletion.delete(any(), any()) }

        // when
        val result = mvc.perform(
            delete("/api/v1/posts/42")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 삭제되었습니다."))
            .andExpect(content().string(Matchers.containsString("\"data\":null")))
        verify(exactly = 1) { media.remove(snapshot(7).imageUrls()) }
        verify(exactly = 1) { deletion.delete(42, 7) }
    }

    @Test
    fun `소유자가 아닌 관리자가 주어졌을 때__삭제하면__미디어와 작성기 전에 거부한다`() {
        // given
        every { posts.findById(any()) } returns Optional.of(snapshot(7))

        // when
        val result = mvc.perform(
            delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(8, "ROLE_ADMIN")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("게시글에 접근할 권한이 없습니다."))
        confirmVerified(media, deletion)
    }

    @Test
    fun `없는 게시글이 주어졌을 때__삭제하면__미디어와 작성기 전에 404를 유지한다`() {
        // given
        every { posts.findById(any()) } returns Optional.empty()

        // when
        val result = mvc.perform(
            delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
        confirmVerified(media, deletion)
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun `검증되지 않은 토큰이 주어졌을 때__삭제하면__모든 업무 포트 전에 401이다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = delete("/api/v1/posts/42").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(posts, media, deletion)
    }

    @Test
    fun `소유자 둘과 익명이 차례로 주어졌을 때__삭제하면__요청 간 주체를 분리한다`() {
        // given
        every { posts.findById(any()) } returnsMany listOf(Optional.of(snapshot(7)), Optional.of(snapshot(8)))
        justRun { media.remove(any()) }
        justRun { deletion.delete(any(), any()) }

        // when
        val owners = longArrayOf(7, 8).map { id ->
            val result = mvc.perform(
                delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")),
            )
            val authentication = SecurityContextHolder.getContext().authentication
            Triple(id, result, authentication)
        }
        val anonymous = mvc.perform(delete("/api/v1/posts/42"))

        // then
        for ((id, result, authentication) in owners) {
            result.andExpect(status().isOk())
            assertThat(authentication).isNull()
            verify(exactly = 1) { deletion.delete(42, id) }
        }
        anonymous.andExpect(status().isUnauthorized())
        verify(exactly = 2) { posts.findById(42) }
        confirmVerified(deletion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `해석할 수 없는 ID가 주어졌을 때__삭제하면__기존 500을 유지한다`(id: String) {
        // given
        val request = delete("/api/v1/posts/$id")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        confirmVerified(posts, media, deletion)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DELETE /api/v1/posts", "DELETE /api/v1/posts/42/extra",
            "DELETE /api/v1/posts/read/42", "DELETE /api/v1/admin/posts/42/extra",
            "PATCH /api/v1/posts/42", "GET /api/v1/posts/42",
        ],
    )
    fun `노출되지 않은 메서드나 경로가 주어졌을 때__요청하면__업무 전에 거부한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(posts, media, deletion)
    }

    @Test
    fun `인증 뒤 작성기 실패가 주어졌을 때__삭제하면__안전한 500을 돌려주고 신원을 비운다`() {
        // given
        every { posts.findById(any()) } returns Optional.of(snapshot(7))
        justRun { media.remove(any()) }
        every { deletion.delete(any(), any()) } throws IllegalStateException("private-db-detail")

        // when
        val result = mvc.perform(
            delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("private-db-detail"))))
        verify(exactly = 1) { deletion.delete(42, 7) }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    companion object {
        @JvmStatic
        fun rejectedTokens(): Stream<Arguments> {
            val expired = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() - 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            val refresh = Jwts.builder()
                .subject("7")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            val invalidIdentity = Jwts.builder()
                .claim("userId", 0)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $expired", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $refresh", "토큰 파싱에 실패했습니다."),
                Arguments.of("Bearer $invalidIdentity", "토큰 파싱에 실패했습니다."),
            )
        }

        private fun snapshot(owner: Long): PostSnapshot {
            return PostSnapshot(
                42,
                owner,
                "제목",
                "내용",
                "COMMUNICATION",
                listOf("https://fixture.invalid/f4/key"),
                LocalDateTime.of(2026, 10, 19, 1, 0),
                LocalDateTime.of(2026, 10, 19, 1, 1),
            )
        }
    }
}
