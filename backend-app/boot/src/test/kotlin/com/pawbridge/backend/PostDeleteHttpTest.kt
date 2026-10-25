package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var posts: PostReader

    @MockitoBean
    lateinit var media: PostMediaRemover

    @MockitoBean
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
    fun given__owner_and_forged_identity_headers__when__deleting__then__use_verified_identity_and_original_envelope(
        role: String,
    ) {
        whenever(posts.findById(42)).thenReturn(Optional.of(snapshot(7)))
        mvc.perform(
            delete("/api/v1/posts/42")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 삭제되었습니다."))
            .andExpect(content().string(Matchers.containsString("\"data\":null")))
        verify(media).remove(snapshot(7).imageUrls())
        verify(deletion).delete(42, 7)
    }

    @Test
    fun given__administrator_who_is_not_owner__when__deleting__then__deny_before_media_and_writer() {
        whenever(posts.findById(42)).thenReturn(Optional.of(snapshot(7)))
        mvc.perform(delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(8, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("게시글에 접근할 권한이 없습니다."))
        verifyNoInteractions(media, deletion)
    }

    @Test
    fun given__missing_post__when__deleting__then__preserve_404_before_media_and_writer() {
        whenever(posts.findById(42)).thenReturn(Optional.empty())
        mvc.perform(delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
        verifyNoInteractions(media, deletion)
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun given__unverified_token__when__deleting__then__401_before_all_business_ports(
        authorization: String?,
        message: String,
    ) {
        val request = delete("/api/v1/posts/42").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(posts, media, deletion)
    }

    @Test
    fun given__two_owners_then_anonymous__when__deleting__then__isolate_principals_between_requests() {
        whenever(posts.findById(42)).thenReturn(Optional.of(snapshot(7)), Optional.of(snapshot(8)))
        for (id in longArrayOf(7, 8)) {
            mvc.perform(
                delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")),
            ).andExpect(status().isOk())
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
            verify(deletion).delete(42, id)
        }
        mvc.perform(delete("/api/v1/posts/42")).andExpect(status().isUnauthorized())
        verify(posts, times(2)).findById(42)
        verifyNoMoreInteractions(deletion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun given__unparseable_id__when__deleting__then__preserve_legacy_500(id: String) {
        mvc.perform(delete("/api/v1/posts/$id").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        verifyNoInteractions(posts, media, deletion)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DELETE /api/v1/posts", "DELETE /api/v1/posts/42/extra",
            "DELETE /api/v1/posts/read/42", "DELETE /api/v1/admin/posts/42/extra",
            "PATCH /api/v1/posts/42", "GET /api/v1/posts/42",
        ],
    )
    fun given__unexposed_method_or_path__when__requesting__then__deny_before_business(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(
            request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(posts, media, deletion)
    }

    @Test
    fun given__writer_failure_after_authentication__when__deleting__then__retain_safe_500_and_clear_identity() {
        whenever(posts.findById(42)).thenReturn(Optional.of(snapshot(7)))
        doThrow(IllegalStateException("private-db-detail")).`when`(deletion).delete(42, 7)
        mvc.perform(delete("/api/v1/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("private-db-detail"))))
        verify(deletion).delete(42, 7)
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
