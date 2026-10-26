package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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

    @MockkBean
    lateinit var listComments: ListComments

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "forged"])
    fun `신뢰할 수 없는 신원의 공개 댓글일 때__목록을 조회하면__원본 일곱 필드를 돌려준다`(
        authorization: String,
    ) {
        // given
        val created = LocalDateTime.of(2026, 10, 19, 0, 1, 2, 123456000)
        val updated = LocalDateTime.of(2026, 10, 19, 1, 2)
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

        every { listComments.read(42) } returns listOf(
            CommentDetails(12, 42, 7, "작성자", "댓글 내용", created, updated),
            CommentDetails(11, 42, 8, null, "다른 댓글", created.minusDays(1), updated),
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
        verify(exactly = 1) { listComments.read(42) }
        confirmVerified(readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `댓글이 없을 때__목록을 조회하면__글 조회와 페이지 봉투 없이 빈 배열을 돌려준다`() {
        // given
        every { listComments.read(404) } returns listOf()

        // when
        val result = mvc.perform(get("/api/v1/comments/posts/read/404"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { listComments.read(404) }
        confirmVerified(readPost, listPosts)
    }

    @Test
    fun `댓글 조회가 민감 정보와 함께 실패할 때__목록을 조회하면__비밀 로그 없이 안전한 500을 돌려준다`() {
        // given
        val failure = IllegalStateException(
            "Bearer synthetic-private-token; synthetic-private-comment",
            java.sql.SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { listComments.read(42) } throws failure

        // when
        val result = try {
            mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer synthetic-private-header"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
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
        verify(exactly = 1) { listComments.read(42) }
        confirmVerified(readPost, listPosts)
    }

    @Test
    fun `기존 읽기 경로에 없는 글일 때__글을 조회하면__댓글 오류 처리가 글 404를 유지한다`() {
        // given
        every { readPost.read(42) } throws PostNotFoundException()

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(listComments, listPosts)
    }

}
