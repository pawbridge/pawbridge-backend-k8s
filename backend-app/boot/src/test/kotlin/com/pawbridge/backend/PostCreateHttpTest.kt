package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.web.PostCreateController
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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
