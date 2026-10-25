package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @Test
    fun given__public_post_with_untrusted_authorization__when__getting_post__then__keep_json_contract() {
        whenever(readPost.read(42)).thenReturn(
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
            ),
        )
        mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))
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
    }

    @Test
    fun given__null_display_values__when__getting_post__then__keep_all_keys_with_explicit_null() {
        whenever(readPost.read(42)).thenReturn(
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
            ),
        )
        mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(content().string(Matchers.containsString("\"authorNickname\":null")))
            .andExpect(content().string(Matchers.containsString("\"imageUrls\":null")))
    }

    @Test
    fun given__absent_post__when__getting_post__then__return_original_404_envelope() {
        whenever(readPost.read(42)).thenThrow(PostNotFoundException())
        mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
            .andExpect(jsonPath("$.data").isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "search", "9223372036854775808"])
    fun given__unparseable_post_id__when__getting_post__then__preserve_legacy_500(value: String) {
        mvc.perform(get("/api/v1/posts/read/$value"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        verifyNoInteractions(readPost)
    }

    @Test
    fun given__post_storage_failure__when__getting_post__then__do_not_mask_it_as_missing_or_fallback() {
        whenever(readPost.read(42)).thenThrow(IllegalStateException("private-detail"))
        mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 에러!"))
            .andExpect(jsonPath("$.data").isEmpty())
    }

    @Test
    fun given__internal_error_dispatch__when__dispatching_to_error_path__then__security_does_not_replace_it_with_403() {
        mvc.perform(
            get("/error").with(
                RequestPostProcessor { request ->
                    request.dispatcherType = DispatcherType.ERROR
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404)
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/posts/read/42")
                    request
                },
            ),
        )
            .andExpect(status().isNotFound())
        verifyNoInteractions(readPost)
    }

    @Test
    fun given__failed_async_stream__when__tomcat_includes_error_page__then__security_does_not_deny_it() {
        mvc.perform(
            get("/error").with(
                RequestPostProcessor { request ->
                    request.dispatcherType = DispatcherType.INCLUDE
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500)
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/notes/stream")
                    request
                },
            ),
        )
            .andExpect(status().isInternalServerError())
        verifyNoInteractions(readPost)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/posts", "GET /api/v1/users/internal/7/nickname", "GET /error",
            "GET /api/posts/read/42", "HEAD /api/v1/posts/read/42", "OPTIONS /api/v1/posts/read/42",
            "POST /api/v1/posts/read/42", "PUT /api/v1/posts/read/42", "DELETE /api/v1/posts/read/42",
        ],
    )
    fun given__unexposed_method_or_path__when__requesting__then__deny_without_business_lookup(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
            .andExpect(status().isForbidden())
        verifyNoInteractions(readPost)
    }
}
