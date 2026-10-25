package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
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
import java.time.LocalDateTime

@WebMvcTest(
    controllers = [CommentReadController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        CommentReadController::class,
        CommentReadExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class CommentListHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var listComments: ListComments

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "forged"])
    fun given__public_comments_with_untrusted_identity__when__getting_list__then__return_original_seven_fields(
        authorization: String,
    ) {
        val created = LocalDateTime.of(2026, 10, 19, 0, 1, 2, 123456000)
        val updated = LocalDateTime.of(2026, 10, 19, 1, 2)
        whenever(listComments.read(42)).thenReturn(
            listOf(
                CommentDetails(12, 42, 7, "작성자", "댓글 내용", created, updated),
                CommentDetails(11, 42, 8, null, "다른 댓글", created.minusDays(1), updated),
            ),
        )
        val request = get("/api/v1/comments/posts/read/42")
            .param("page", "invalid")
            .param("size", "0")
            .param("postId", "999")
            .param("authorId", "999")
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
        if (authorization == "malformed") {
            request.header("Authorization", "Bearer invalid")
        }
        if (authorization == "forged") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(999, "ROLE_ADMIN") + "forged")
        }

        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].length()").value(7))
            .andExpect(jsonPath("$.data[0].commentId").value(12))
            .andExpect(jsonPath("$.data[0].postId").value(42))
            .andExpect(jsonPath("$.data[0].authorId").value(7))
            .andExpect(jsonPath("$.data[0].authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data[0].content").value("댓글 내용"))
            .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-19T00:01:02.123456"))
            .andExpect(jsonPath("$.data[0].updatedAt").value("2026-10-19T01:02:00"))
            .andExpect(jsonPath("$.data[1].length()").value(7))
            .andExpect(jsonPath("$.data[1].commentId").value(11))
            .andExpect(jsonPath("$.data[1].authorId").value(8))
            .andExpect(content().string(Matchers.containsString("\"authorNickname\":null")))
        verify(listComments).read(42)
        verifyNoInteractions(readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__no_comments__when__getting_list__then__return_empty_array_without_post_lookup_or_page_envelope() {
        whenever(listComments.read(404)).thenReturn(listOf())

        mvc.perform(get("/api/v1/comments/posts/read/404"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(listComments).read(404)
        verifyNoInteractions(readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun given__unparseable_post_id__when__getting_comments__then__preserve_legacy_safe_500(postId: String) {
        mvc.perform(get("/api/v1/comments/posts/read/$postId"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verifyNoInteractions(listComments, readPost, listPosts)
    }

    @Test
    fun given__comment_failure_with_sensitive_details__when__getting_list__then__return_safe_500_without_secret_logs() {
        val failure = IllegalStateException(
            "Bearer synthetic-private-token; synthetic-private-comment",
            java.sql.SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        whenever(listComments.read(42)).thenThrow(failure)
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer synthetic-private-header"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
            assertThat(appender.list).allSatisfy { event ->
                assertThat(event.throwableProxy).isNull()
                assertThat(event.formattedMessage).doesNotContain(
                    "synthetic-private-token",
                    "synthetic-private-comment",
                    "synthetic-private-password",
                    "synthetic-private-header",
                    "jdbc:postgresql:",
                )
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        verify(listComments).read(42)
        verifyNoInteractions(readPost, listPosts)
    }

    @Test
    fun given__missing_post_on_existing_read_path__when__getting_post__then__comment_advice_preserves_post_404() {
        whenever(readPost.read(42)).thenThrow(PostNotFoundException())

        mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(listComments, listPosts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/comments", "GET /api/v1/comments/posts/42", "GET /api/v1/comments/posts/read",
            "GET /api/v1/comments/posts/read/", "GET /api/v1/comments/posts/read/42/", "GET /api/v1/comments/posts/read/42/extra",
            "GET /api/v1/comments/posts/reading/42", "GET /api/comments/posts/read/42", "GET /api/v1/admin/comments/posts/read/42",
            "HEAD /api/v1/comments/posts/read/42", "OPTIONS /api/v1/comments/posts/read/42",
            "POST /api/v1/comments/posts/read/42", "PUT /api/v1/comments/posts/read/42",
            "PATCH /api/v1/comments/posts/read/42", "DELETE /api/v1/comments/posts/read/42",
        ],
    )
    fun given__unexposed_method_or_adjacent_path__when__requesting__then__deny_without_comment_or_post_lookup(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42"])
    fun given__existing_post_write_without_token__when__requesting__then__preserve_jwt_authentication(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
        verifyNoInteractions(listComments, readPost, listPosts)
    }
}
