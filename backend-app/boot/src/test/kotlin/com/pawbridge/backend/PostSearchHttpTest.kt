package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var searchPosts: SearchPosts

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed"])
    fun `신뢰할 수 없는 신원의 공개 검색이 주어졌을 때__결과를 조회하면__원본 키워드와 아홉 필드를 유지한다`(
        bearer: String,
    ) {
        // given
        val keyword = "  강아지  "
        val created = LocalDateTime.of(2026, 10, 19, 0, 1, 2, 123456000)
        val updated = LocalDateTime.of(2026, 10, 19, 1, 2)
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

        every { searchPosts.search(any()) } returns
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
            )

        // when
        val result = mvc.perform(request)

        // then
        result
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
        verify(exactly = 1) { searchPosts.search(keyword) }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `검색 결과가 없을 때__결과를 조회하면__페이지 봉투 없이 빈 배열을 돌려준다`() {
        // given
        every { searchPosts.search(any()) } returns listOf()

        // when
        val result = mvc.perform(get("/api/v1/posts/search").param("keyword", "일치없음"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { searchPosts.search("일치없음") }
    }

    @Test
    fun `키워드가 없을 때__결과를 조회하면__업무 호출 없이 필수 파라미터 400을 유지한다`() {
        // given
        val request = get("/api/v1/posts/search")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 'keyword'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(searchPosts)
    }

    @Test
    fun `기존 읽기 경로에 없는 게시글이 주어졌을 때__조회하면__검색 어드바이스가 원래 404를 바꾸지 않는다`() {
        // given
        every { readPost.read(any()) } throws PostNotFoundException()

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { readPost.read(42) }
        confirmVerified(searchPosts, listPosts)
    }

    @ParameterizedTest
    @MethodSource("searchFailures")
    fun `민감한 값이 담긴 검색 실패가 주어졌을 때__결과를 조회하면__비밀 로그 없이 범위 한정 오류를 돌려준다`(
        expectedStatus: Int,
        expectedMessage: String,
        failure: RuntimeException,
    ) {
        // given
        val keyword = "synthetic-private-keyword"
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { searchPosts.search(any()) } throws failure

        // when
        val result = try {
            mvc.perform(
                get("/api/v1/posts/search")
                    .param("keyword", keyword)
                    .header("Authorization", "Bearer synthetic-private-header"),
            )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
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
        verify(exactly = 1) { searchPosts.search(keyword) }
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
    fun `노출되지 않은 메서드나 인접 경로가 주어졌을 때__요청하면__검색 없이 거부한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]).param("keyword", "강아지"),
        )

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(searchPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "DELETE"])
    fun `토큰 없이 검색 경로와 겹치는 쓰기 메서드가 주어졌을 때__요청하면__쓰기 인증을 유지한다`(
        method: String,
    ) {
        // given
        val httpMethod = org.springframework.http.HttpMethod.valueOf(method)

        // when
        val result = mvc.perform(request(httpMethod, "/api/v1/posts/search"))

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
        confirmVerified(searchPosts)
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
