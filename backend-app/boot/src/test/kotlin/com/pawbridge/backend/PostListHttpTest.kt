package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
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
class PostListHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @Test
    fun given__public_posts__when__getting_list_without_token__then__return_array_with_original_nine_fields() {
        whenever(listPosts.read()).thenReturn(listOf(post()))

        mvc.perform(get("/api/v1/posts/read"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].length()").value(9))
            .andExpect(jsonPath("$.data[0].postId").value(42))
            .andExpect(jsonPath("$.data[0].authorId").value(7))
            .andExpect(jsonPath("$.data[0].authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data[0].title").value("제목"))
            .andExpect(jsonPath("$.data[0].content").value("본문"))
            .andExpect(jsonPath("$.data[0].boardType").value("REPORT"))
            .andExpect(jsonPath("$.data[0].imageUrls.length()").value(3))
            .andExpect(jsonPath("$.data[0].imageUrls[0]").value("https://fixture.invalid/가.jpg"))
            .andExpect(jsonPath("$.data[0].imageUrls[1]").isEmpty())
            .andExpect(jsonPath("$.data[0].imageUrls[2]").value("https://fixture.invalid/가.jpg"))
            .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-18T00:01:02.123456"))
            .andExpect(jsonPath("$.data[0].updatedAt").value("2026-10-18T01:02:00"))
        verify(listPosts).read()
        verifyNoInteractions(readPost)
    }

    @Test
    fun given__unrelated_queries_and_forged_identity__when__getting_public_list__then__do_not_bind_or_filter_inputs() {
        whenever(listPosts.read()).thenReturn(
            listOf(
                post(),
                PostDetails(
                    43,
                    8,
                    null,
                    "다른 글",
                    "내용",
                    "MISSING",
                    null,
                    LocalDateTime.of(2026, 10, 17, 0, 1),
                    LocalDateTime.of(2026, 10, 17, 0, 2),
                ),
            ),
        )

        mvc.perform(
            get("/api/v1/posts/read")
                .param("page", "invalid")
                .param("size", "0")
                .param("boardType", "COMMUNICATION")
                .param("boardId", "999")
                .param("authorId", "999")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer invalid"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].authorId").value(7))
            .andExpect(jsonPath("$.data[0].boardType").value("REPORT"))
            .andExpect(jsonPath("$.data[1].postId").value(43))
            .andExpect(jsonPath("$.data[1].authorId").value(8))
            .andExpect(jsonPath("$.data[1].boardType").value("MISSING"))
            .andExpect(jsonPath("$.data[1].length()").value(9))
            .andExpect(content().string(Matchers.containsString("\"authorNickname\":null")))
            .andExpect(content().string(Matchers.containsString("\"imageUrls\":null")))
        verify(listPosts).read()
        verifyNoInteractions(readPost)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__no_posts__when__getting_list__then__return_empty_array_without_page_envelope() {
        whenever(listPosts.read()).thenReturn(listOf())

        mvc.perform(get("/api/v1/posts/read"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(listPosts).read()
        verifyNoInteractions(readPost)
    }

    @Test
    fun given__list_failure_with_sensitive_details__when__getting_list__then__reuse_safe_read_error_without_logging_secrets() {
        val failure = IllegalStateException(
            "Bearer synthetic-private-token; synthetic-private-content",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        whenever(listPosts.read()).thenThrow(failure)
        try {
            mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer synthetic-private-header"))
                .andExpect(status().isInternalServerError())
                .andExpect(
                    content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
                )
            assertThat(appender.list).allSatisfy { event ->
                assertThat(event.throwableProxy).isNull()
                assertThat(event.formattedMessage).doesNotContain(
                    "synthetic-private-token",
                    "synthetic-private-content",
                    "synthetic-private-password",
                    "synthetic-private-header",
                    "jdbc:postgresql:",
                )
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        verify(listPosts).read()
        verifyNoInteractions(readPost)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/posts", "GET /api/v1/posts/search/extra", "GET /api/posts/read",
            "GET /api/v1/posts/read/42/extra",
            "HEAD /api/v1/posts/read", "OPTIONS /api/v1/posts/read", "POST /api/v1/posts/read", "PATCH /api/v1/posts/read",
        ],
    )
    fun given__unexposed_method_or_path__when__requesting__then__deny_without_list_or_single_lookup(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
            .andExpect(status().isForbidden())
        verifyNoInteractions(listPosts, readPost)
    }

    companion object {
        private fun post(): PostDetails {
            return PostDetails(
                42,
                7,
                "작성자",
                "제목",
                "본문",
                "REPORT",
                listOf("https://fixture.invalid/가.jpg", null, "https://fixture.invalid/가.jpg"),
                LocalDateTime.of(2026, 10, 18, 0, 1, 2, 123456000),
                LocalDateTime.of(2026, 10, 18, 1, 2),
            )
        }
    }
}
