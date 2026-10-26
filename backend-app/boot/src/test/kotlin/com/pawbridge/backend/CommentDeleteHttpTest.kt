package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Date
import java.util.stream.Stream

@WebMvcTest(
    controllers = [CommentDeleteController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        CommentDeleteController::class,
        CommentDeleteExceptionHandler::class,
        CommentCreateController::class,
        CommentCreateExceptionHandler::class,
        CommentReadController::class,
        CommentReadExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class CommentDeleteHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var deletion: CommentDeletion

    @MockkBean
    lateinit var createComment: CreateComment

    @MockkBean
    lateinit var listComments: ListComments

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `검증된 소유자와 위조 헤더일 때__댓글을 지우면__주체를 쓰고 원본 null 봉투를 돌려준다`(
        role: String,
    ) {
        // given
        justRun { deletion.delete(12, 7) }

        // when
        val result = mvc.perform(
            delete("/api/v1/comments/12")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("authorId", "999"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { deletion.delete(12, 7) }
        confirmVerified(createComment, listComments, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{", "null", "{\"content\":null,\"authorId\":999}"])
    fun `필요 없거나 깨진 JSON 본문일 때__댓글을 지우면__새 검증 없이 본문을 무시한다`(
        body: String,
    ) {
        // given
        justRun { deletion.delete(12, 7) }

        // when
        val result = mvc.perform(authenticatedDelete("12").contentType(MediaType.APPLICATION_JSON).content(body))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { deletion.delete(12, 7) }
        confirmVerified(createComment, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(longs = [12, 0, -1])
    fun `포트가 댓글을 찾지 못할 때__댓글을 지우면__새 ID 검증 없이 404를 유지한다`(
        commentId: Long,
    ) {
        // given
        every { deletion.delete(commentId, 7) } throws CommentNotFoundException()

        // when
        val result = mvc.perform(authenticatedDelete(commentId.toString()))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"댓글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { deletion.delete(commentId, 7) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `다른 작성자의 댓글일 때__어떤 역할이든 지우면__관리자 예외 없이 소유자 403을 유지한다`(
        role: String,
    ) {
        // given
        every { deletion.delete(12, 7) } throws CommentNotOwnedException()

        // when
        val result = mvc.perform(delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(
                content().json(
                    "{\"code\":403,\"message\":\"댓글에 접근할 권한이 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { deletion.delete(12, 7) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `파싱할 수 없는 댓글 ID와 유효한 신원일 때__지우면__400을 응답한다`(
        commentId: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedDelete(commentId))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(deletion, createComment, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("deletionFailures")
    fun `인증 뒤 삭제가 실패할 때__댓글을 지우면__401이 아닌 안전한 오류를 주고 민감 정보를 남기지 않는다`(
        expectedStatus: Int,
        message: String,
        failure: RuntimeException,
    ) {
        // given
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { deletion.delete(12, 7) } throws failure

        // when
        val result = try {
            mvc.perform(
                authenticatedDelete("12")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"synthetic-private-comment\"}"),
            )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
            .andExpect(status().`is`(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedStatus))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.data").isEmpty())
        assertThat(appender.list).allSatisfy { event ->
            assertThat(event.throwableProxy).isNull()
            assertThat(event.formattedMessage).doesNotContain(
                "synthetic-private-token",
                "synthetic-private-comment",
                "synthetic-private-password",
                "synthetic-private-url",
                "Bearer ",
                "jdbc:postgresql:",
            )
        }
        verify(exactly = 1) { deletion.delete(12, 7) }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `기존 댓글 읽기와 작성과 글 404가 있을 때__요청하면__삭제의 인증과 오류 처리 범위가 유지된다`() {
        // given
        val time = LocalDateTime.of(2026, 10, 19, 12, 30)
        val comment = CommentDetails(12, 42, 7, "작성자", "댓글", time, time)

        every { listComments.read(42) } returns listOf(comment)
        every { createComment.create(42, 7, "댓글") } returns comment
        every { readPost.read(42) } throws PostNotFoundException()

        // when
        val readResult = mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
        val createResult = mvc.perform(
            post("/api/v1/comments/posts/42")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"댓글\"}"),
        )
        val postResult = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        readResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].commentId").value(12))
        createResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."))
        postResult
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(deletion, listPosts)
    }

    companion object {
        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            val expired = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(1))
                .signWith(F3TestInputs.KEY)
                .compact()
            val otherKey = Keys.hmacShaKeyFor(
                "f10-synthetic-other-signing-key-only-012345678901234567890123456789".toByteArray(Charsets.UTF_8),
            )
            val forged = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() + 3_600_000))
                .signWith(otherKey)
                .compact()
            val refresh = Jwts.builder()
                .subject("synthetic@example.invalid")
                .expiration(Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $expired", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $forged", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $refresh", "토큰 파싱에 실패했습니다."),
            )
        }

        @JvmStatic
        fun deletionFailures(): Stream<Arguments> {
            val failure = IllegalStateException(
                "Bearer synthetic-private-token; synthetic-private-comment",
                SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
            )
            failure.addSuppressed(RuntimeException("https://fixture.invalid/synthetic-private-url"))
            return Stream.of(
                Arguments.of(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", UnsupportedCommentCharactersException(failure)),
                Arguments.of(500, "서버 에러!", failure),
            )
        }

        private fun authenticatedDelete(commentId: String): MockHttpServletRequestBuilder {
            return delete("/api/v1/comments/$commentId")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
    }
}
