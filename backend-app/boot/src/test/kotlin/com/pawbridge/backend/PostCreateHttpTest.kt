package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.web.PostCreateController
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.eqValue
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.Date

@WebMvcTest(
    controllers = [PostCreateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostCreateController::class,
        PostCreateExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PostCreateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var createPost: CreatePost

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__authenticated_writer_and_forged_headers__when__creating__then__use_verified_id_and_legacy_response(
        role: String,
    ) {
        whenever(
            createPost.create(
                eq(7L),
                eq("제목"),
                eq("내용"),
                eqValue(PostBoardType.COMMUNICATION),
                anyList(),
            ),
        ).thenReturn(details(7))
        mvc.perform(
            multipart("/api/v1/posts")
                .param("title", "제목")
                .param("content", "내용")
                .param("boardType", "COMMUNICATION")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("게시글이 생성되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
        verify(createPost).create(7L, "제목", "내용", PostBoardType.COMMUNICATION, listOf())
    }

    @Test
    fun given__missing_or_invalid_token__when__posting__then__do_not_invoke_writing_use_case() {
        mvc.perform(multipart("/api/v1/posts").header("X-User-Id", "7"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("인증 토큰이 필요합니다."))
        mvc.perform(multipart("/api/v1/posts").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 토큰입니다."))
        verifyNoInteractions(createPost)
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    fun given__signed_invalid_identity__when__posting__then__reject_before_writing(claims: Map<String, Any>) {
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()
        mvc.perform(multipart("/api/v1/posts").header("Authorization", "Bearer $token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."))
        verifyNoInteractions(createPost)
    }

    @Test
    fun given__two_writers_and_anonymous_next_request__when__posting__then__do_not_retain_the_previous_identity() {
        whenever(
            createPost.create(anyLong(), anyString(), anyString(), anyNonNull<PostBoardType>(), anyList()),
        ).thenAnswer { call -> details(call.getArgument<Long>(0)) }
        for (id in longArrayOf(7, 8)) {
            mvc.perform(
                multipart("/api/v1/posts")
                    .param("title", "제목")
                    .param("content", "내용")
                    .param("boardType", "COMMUNICATION")
                    .header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")),
            )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorId").value(id.toInt()))
        }
        mvc.perform(multipart("/api/v1/posts")).andExpect(status().isUnauthorized())
        verify(createPost, times(2)).create(
            anyLong(),
            anyString(),
            anyString(),
            anyNonNull<PostBoardType>(),
            anyList(),
        )
    }

    @Test
    fun given__business_failure_after_authentication__when__posting__then__do_not_relabel_it_as_401() {
        whenever(
            createPost.create(anyLong(), anyString(), anyString(), anyNonNull<PostBoardType>(), anyList()),
        ).thenThrow(IllegalStateException("private-db-detail"))
        mvc.perform(
            multipart("/api/v1/posts")
                .param("title", "제목")
                .param("content", "내용")
                .param("boardType", "COMMUNICATION")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
    }

    companion object {
        private fun details(authorId: Long): PostDetails {
            return PostDetails(
                42,
                authorId,
                "작성자",
                "제목",
                "내용",
                "COMMUNICATION",
                listOf(),
                LocalDateTime.of(2026, 10, 18, 12, 0),
                LocalDateTime.of(2026, 10, 18, 12, 0),
            )
        }
    }
}
