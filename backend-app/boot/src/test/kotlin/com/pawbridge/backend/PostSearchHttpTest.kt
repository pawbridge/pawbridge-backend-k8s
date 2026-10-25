package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostSearchController
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import java.util.stream.Stream

@WebMvcTest(
    controllers = [PostSearchController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PostSearchController::class,
        PostSearchExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PostSearchHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var searchPosts: SearchPosts

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed"])
    fun given__public_search_with_untrusted_identity__when__getting_results__then__preserve_raw_keyword_and_nine_fields(
        bearer: String,
    ) {
        val keyword = "  강아지  "
        val created = LocalDateTime.of(2026, 10, 19, 0, 1, 2, 123456000)
        val updated = LocalDateTime.of(2026, 10, 19, 1, 2)
        whenever(searchPosts.search(keyword)).thenReturn(
            listOf(
                PostDetails(
                    42,
                    7,
                    "작성자",
                    "제목",
                    "본문",
                    "REPORT",
                    listOf("https://fixture.invalid/가.jpg", null, "https://fixture.invalid/가.jpg"),
                    created,
                    updated,
                ),
                PostDetails(43, 8, null, "다른 글", "내용", "MISSING", null, created, updated),
            ),
        )
        val request = get("/api/v1/posts/search")
            .param("keyword", keyword)
            .param("page", "invalid")
            .param("size", "0")
            .param("boardType", "COMMUNICATION")
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
        if (bearer == "malformed") {
            request.header("Authorization", "Bearer invalid")
        }

        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
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
            .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-19T00:01:02.123456"))
            .andExpect(jsonPath("$.data[0].updatedAt").value("2026-10-19T01:02:00"))
            .andExpect(jsonPath("$.data[1].length()").value(9))
            .andExpect(jsonPath("$.data[1].postId").value(43))
            .andExpect(jsonPath("$.data[1].authorId").value(8))
            .andExpect(jsonPath("$.data[1].boardType").value("MISSING"))
            .andExpect(content().string(Matchers.containsString("\"authorNickname\":null")))
            .andExpect(content().string(Matchers.containsString("\"imageUrls\":null")))
        verify(searchPosts).search(keyword)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__no_search_results__when__getting_results__then__return_empty_array_without_paging_envelope() {
        whenever(searchPosts.search("일치없음")).thenReturn(listOf())

        mvc.perform(get("/api/v1/posts/search").param("keyword", "일치없음"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(searchPosts).search("일치없음")
    }

    @Test
    fun given__missing_keyword__when__getting_results__then__preserve_required_parameter_400_without_business_call() {
        mvc.perform(get("/api/v1/posts/search"))
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 'keyword'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(searchPosts)
    }

    @Test
    fun given__missing_post_on_existing_read_path__when__getting_post__then__search_advice_does_not_replace_original_404() {
        whenever(readPost.read(42)).thenThrow(PostNotFoundException())

        mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(searchPosts, listPosts)
    }

    @ParameterizedTest
    @MethodSource("searchFailures")
    fun given__search_failure_with_sensitive_values__when__getting_results__then__safe_scoped_error_without_secret_logs(
        expectedStatus: Int,
        expectedMessage: String,
        failure: RuntimeException,
    ) {
        val keyword = "synthetic-private-keyword"
        whenever(searchPosts.search(keyword)).thenThrow(failure)
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(
                get("/api/v1/posts/search")
                    .param("keyword", keyword)
                    .header("Authorization", "Bearer synthetic-private-header"),
            )
                .andExpect(status().`is`(expectedStatus))
                .andExpect(
                    content().json(
                        "{\"code\":" + expectedStatus + ",\"message\":\"" + expectedMessage + "\",\"data\":null}",
                        JsonCompareMode.STRICT,
                    ),
                )
            assertThat(appender.list).allSatisfy { event ->
                assertThat(event.throwableProxy).isNull()
                assertThat(event.formattedMessage).doesNotContain(
                    keyword,
                    "synthetic-private-token",
                    "synthetic-private-content",
                    "private-image",
                    "synthetic-private-password",
                    "synthetic-private-header",
                    "jdbc:postgresql:",
                )
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        verify(searchPosts).search(keyword)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/posts", "GET /api/v1/posts/search/42", "GET /api/v1/posts/search/",
            "GET /api/v1/posts/searching", "GET /api/posts/search",
            "HEAD /api/v1/posts/search", "OPTIONS /api/v1/posts/search", "POST /api/v1/posts/search",
            "PATCH /api/v1/posts/search",
        ],
    )
    fun given__unexposed_method_or_adjacent_path__when__requesting__then__deny_without_search(request: String) {
        val parts = request.split(" ", limit = 2)
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]).param("keyword", "강아지"))
            .andExpect(status().isForbidden())
        verifyNoInteractions(searchPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "DELETE"])
    fun given__write_method_overlapping_search_path_without_token__when__requesting__then__preserve_write_authentication(
        method: String,
    ) {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1/posts/search"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
        verifyNoInteractions(searchPosts)
    }

    companion object {
        @JvmStatic
        fun searchFailures(): Stream<Arguments> {
            val failure = IllegalStateException(
                "Bearer synthetic-private-token; synthetic-private-content; https://fixture.invalid/private-image",
                SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
            )
            return Stream.of(
                Arguments.of(400, "검색 입력을 확인해 주세요.", InvalidPostSearchInputException(failure)),
                Arguments.of(503, "검색 서비스를 사용할 수 없습니다.", PostSearchUnavailableException(failure)),
                Arguments.of(500, "서버 에러!", failure),
            )
        }
    }
}
