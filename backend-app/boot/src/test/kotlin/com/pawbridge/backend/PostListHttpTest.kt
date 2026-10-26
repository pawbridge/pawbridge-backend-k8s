package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostDetails
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @Test
    fun `공개 게시글이 주어졌을 때__토큰 없이 목록을 조회하면__원래 아홉 필드의 배열을 돌려준다`() {
        // given
        every { listPosts.read() } returns listOf(post())

        // when
        val result = mvc.perform(get("/api/v1/posts/read"))

        // then
        result
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
        verify(exactly = 1) { listPosts.read() }
        confirmVerified(listPosts, readPost)
    }

    @Test
    fun `무관한 쿼리와 위조 신원이 주어졌을 때__공개 목록을 조회하면__입력을 바인딩하거나 거르지 않는다`() {
        // given
        every { listPosts.read() } returns listOf(
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
        )

        // when
        val result = mvc.perform(
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

        // then
        result
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
        verify(exactly = 1) { listPosts.read() }
        confirmVerified(listPosts, readPost)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `게시글이 없을 때__목록을 조회하면__페이지 봉투 없이 빈 배열을 돌려준다`() {
        // given
        every { listPosts.read() } returns listOf()

        // when
        val result = mvc.perform(get("/api/v1/posts/read"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { listPosts.read() }
        confirmVerified(listPosts, readPost)
    }

    @Test
    fun `민감한 상세가 담긴 목록 실패가 주어졌을 때__목록을 조회하면__비밀을 기록하지 않고 안전한 읽기 오류를 쓴다`() {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token; synthetic-private-content",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { listPosts.read() } throws failure

        // when
        val result = try {
            mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer synthetic-private-header"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
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
        verify(exactly = 1) { listPosts.read() }
        confirmVerified(listPosts, readPost)
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
