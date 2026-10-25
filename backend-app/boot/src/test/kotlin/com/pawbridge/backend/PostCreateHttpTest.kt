package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.web.PostCreateController
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var createPost: CreatePost

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `인증된 작성자와 위조 헤더가 주어졌을 때__등록하면__검증된 ID와 기존 응답 형식을 쓴다`(
        role: String,
    ) {
        // given
        every { createPost.create(any(), any(), any(), any(), any()) } returns details(7)

        // when
        val result = mvc.perform(
            multipart("/api/v1/posts")
                .param("title", "제목")
                .param("content", "내용")
                .param("boardType", "COMMUNICATION")
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
            .andExpect(jsonPath("$.message").value("게시글이 생성되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.authorId").value(7))
        verify(exactly = 1) { createPost.create(7L, "제목", "내용", PostBoardType.COMMUNICATION, listOf()) }
        confirmVerified(createPost)
    }

    @Test
    fun `토큰이 없거나 잘못되었을 때__등록하면__쓰기 유스케이스를 호출하지 않는다`() {
        // given
        val missing = multipart("/api/v1/posts").header("X-User-Id", "7")
        val invalid = multipart("/api/v1/posts").header("Authorization", "Bearer invalid")

        // when
        val missingResult = mvc.perform(missing)
        val invalidResult = mvc.perform(invalid)

        // then
        missingResult
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("인증 토큰이 필요합니다."))
        invalidResult
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 토큰입니다."))
        confirmVerified(createPost)
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    fun `서명됐지만 잘못된 신원이 주어졌을 때__등록하면__쓰기 전에 거부한다`(claims: Map<String, Any>) {
        // given
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()

        // when
        val result = mvc.perform(multipart("/api/v1/posts").header("Authorization", "Bearer $token"))

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."))
        confirmVerified(createPost)
    }

    @Test
    fun `작성자 둘과 익명 다음 요청이 주어졌을 때__등록하면__이전 신원을 유지하지 않는다`() {
        // given
        every { createPost.create(any(), any(), any(), any(), any()) } answers { details(firstArg()) }

        // when
        val results = longArrayOf(7, 8).map { id ->
            id to mvc.perform(
                multipart("/api/v1/posts")
                    .param("title", "제목")
                    .param("content", "내용")
                    .param("boardType", "COMMUNICATION")
                    .header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")),
            )
        }
        val anonymous = mvc.perform(multipart("/api/v1/posts"))

        // then
        for ((id, result) in results) {
            result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorId").value(id.toInt()))
        }
        anonymous.andExpect(status().isUnauthorized())
        verify(exactly = 2) { createPost.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `인증 뒤 업무 실패가 주어졌을 때__등록하면__401로 바꾸지 않고 500을 돌려준다`() {
        // given
        every {
            createPost.create(any(), any(), any(), any(), any())
        } throws IllegalStateException("private-db-detail")

        // when
        val result = mvc.perform(
            multipart("/api/v1/posts")
                .param("title", "제목")
                .param("content", "내용")
                .param("boardType", "COMMUNICATION")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
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
