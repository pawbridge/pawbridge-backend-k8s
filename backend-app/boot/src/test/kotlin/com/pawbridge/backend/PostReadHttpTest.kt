package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(
    controllers = [PostReadController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PostReadHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @Test
    fun `신뢰할 수 없는 인증 헤더의 공개 게시글이 주어졌을 때__조회하면__JSON 계약을 유지한다`() {
        // given
        every { readPost.read(any()) } returns
            PostDetails(
                42,
                7,
                "작성자",
                "제목",
                "내용",
                "REPORT",
                listOf("https://fixture.invalid/가.jpg", null, "https://fixture.invalid/가.jpg"),
                LocalDateTime.of(2026, 10, 18, 0, 1, 2, 123456000),
                LocalDateTime.of(2026, 10, 18, 1, 2),
            )

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data.postId").value(42))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.title").value("제목"))
            .andExpect(jsonPath("$.data.content").value("내용"))
            .andExpect(jsonPath("$.data.boardType").value("REPORT"))
            .andExpect(jsonPath("$.data.imageUrls.length()").value(3))
            .andExpect(jsonPath("$.data.imageUrls[0]").value("https://fixture.invalid/가.jpg"))
            .andExpect(jsonPath("$.data.imageUrls[1]").isEmpty())
            .andExpect(jsonPath("$.data.imageUrls[2]").value("https://fixture.invalid/가.jpg"))
            .andExpect(jsonPath("$.data.createdAt").value("2026-10-18T00:01:02.123456"))
            .andExpect(jsonPath("$.data.updatedAt").value("2026-10-18T01:02:00"))
        verify(exactly = 1) { readPost.read(42) }
    }

    @Test
    fun `표시 값이 null일 때__조회하면__모든 키를 명시적 null로 유지한다`() {
        // given
        every { readPost.read(any()) } returns
            PostDetails(
                42,
                7,
                null,
                "제목",
                "내용",
                "MISSING",
                null,
                LocalDateTime.of(2026, 10, 18, 0, 1),
                LocalDateTime.of(2026, 10, 18, 0, 2),
            )

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(content().string(Matchers.containsString("\"authorNickname\":null")))
            .andExpect(content().string(Matchers.containsString("\"imageUrls\":null")))
        verify(exactly = 1) { readPost.read(42) }
    }

    @Test
    fun `없는 게시글이 주어졌을 때__조회하면__기존 404 응답 형식을 돌려준다`() {
        // given
        every { readPost.read(any()) } throws PostNotFoundException()

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
            .andExpect(jsonPath("$.data").isEmpty())
        verify(exactly = 1) { readPost.read(42) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "search", "9223372036854775808"])
    fun `해석할 수 없는 게시글 ID가 주어졌을 때__조회하면__기존 500을 유지한다`(value: String) {
        // given
        val request = get("/api/v1/posts/read/$value")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        confirmVerified(readPost)
    }

    @Test
    fun `게시글 저장소 실패가 주어졌을 때__조회하면__없음이나 대체값으로 가리지 않는다`() {
        // given
        every { readPost.read(any()) } throws IllegalStateException("private-detail")

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(jsonPath("$.data").isEmpty())
        verify(exactly = 1) { readPost.read(42) }
    }

    @Test
    fun `내부 오류 디스패치가 주어졌을 때__오류 경로로 보내면__보안이 403으로 바꾸지 않는다`() {
        // given
        val errorRequest = get("/error")

        // when
        val result = mvc.perform(
            errorRequest.with(
                RequestPostProcessor { request ->
                    request.dispatcherType = DispatcherType.ERROR
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404)
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/posts/read/42")
                    request
                },
            ),
        )

        // then
        result.andExpect(status().isNotFound())
        confirmVerified(readPost)
    }

    @Test
    fun `실패한 비동기 스트림이 주어졌을 때__톰캣이 오류 페이지를 포함하면__보안이 거부하지 않는다`() {
        // given
        val errorRequest = get("/error")

        // when
        val result = mvc.perform(
            errorRequest.with(
                RequestPostProcessor { request ->
                    request.dispatcherType = DispatcherType.INCLUDE
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500)
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/notes/stream")
                    request
                },
            ),
        )

        // then
        result.andExpect(status().isInternalServerError())
        confirmVerified(readPost)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/posts", "GET /api/v1/users/internal/7/nickname", "GET /error",
            "GET /api/posts/read/42", "HEAD /api/v1/posts/read/42", "OPTIONS /api/v1/posts/read/42",
            "POST /api/v1/posts/read/42", "PUT /api/v1/posts/read/42", "DELETE /api/v1/posts/read/42",
        ],
    )
    fun `노출되지 않은 메서드나 경로가 주어졌을 때__요청하면__업무 조회 없이 거부한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(readPost)
    }
}
