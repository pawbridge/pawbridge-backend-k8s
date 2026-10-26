package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
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
    controllers = [CommentCreateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
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
class CommentCreateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

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
    fun `서명된 작성자와 위조 신원일 때__댓글을 만들면__검증된 ID와 원본 일곱 필드를 쓴다`(
        role: String,
    ) {
        // given
        every { createComment.create(42, 7, "  댓글 본문  ") } returns details(7, "  댓글 본문  ")

        // when
        val result = mvc.perform(
            post("/api/v1/comments/posts/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  댓글 본문  \",\"authorId\":999,\"postId\":999}")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."))
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(jsonPath("$.data.commentId").value(12))
            .andExpect(jsonPath("$.data.postId").value(42))
            .andExpect(jsonPath("$.data.authorId").value(7))
            .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
            .andExpect(jsonPath("$.data.content").value("  댓글 본문  "))
            .andExpect(jsonPath("$.data.createdAt").value("2026-10-19T14:01:02.123456789"))
            .andExpect(jsonPath("$.data.updatedAt").value("2026-10-19T14:01:02.123456789"))
        verify(exactly = 1) { createComment.create(42, 7, "  댓글 본문  ") }
        confirmVerified(createComment, listComments, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("acceptedContents")
    fun `비공백 내용일 때__댓글을 만들면__공백과 원문을 보존하고 최대 길이를 두지 않는다`(
        content: String,
    ) {
        // given
        every { createComment.create(42, 7, content) } returns details(7, content)

        // when
        val result = mvc.perform(authenticatedPost("42").content(mapper.writeValueAsString(mapOf("content" to content))))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").value(content))
        verify(exactly = 1) { createComment.create(42, 7, content) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \"}", "{\"content\":\" \\t\\r\\n \"}"])
    fun `내용이 없거나 null이거나 ASCII 공백일 때__댓글을 만들면__업무 호출 없이 400을 돌려준다`(
        body: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedPost("42").content(body))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"content: 내용은 필수입니다\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(createComment, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "null", "{", "[]"])
    fun `본문이 없거나 JSON이 깨졌을 때__댓글을 만들면__업무 호출 없이 안전한 400을 돌려준다`(
        body: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedPost("42").content(body))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(createComment, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `파싱할 수 없는 글 ID와 유효한 신원일 때__댓글을 만들면__400을 응답한다`(
        postId: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedPost(postId).content("{\"content\":\"댓글\"}"))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(createComment, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `0 이하의 글 ID일 때__댓글을 만들면__부모 조회가 404를 결정하게 한다`(postId: Long) {
        // given
        every { createComment.create(postId, 7, "댓글") } throws PostNotFoundException()

        // when
        val result = mvc.perform(authenticatedPost(postId.toString()).content("{\"content\":\"댓글\"}"))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { createComment.create(postId, 7, "댓글") }
    }

    @ParameterizedTest
    @MethodSource("businessFailures")
    fun `인증 뒤 업무 실패가 나면__댓글을 만들면__안전한 오류 계약을 지키고 비밀을 로그에 남기지 않는다`(
        status: Int,
        message: String,
        failure: RuntimeException,
    ) {
        // given
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { createComment.create(42, 7, "synthetic-private-comment") } throws failure

        // when
        val result = try {
            mvc.perform(authenticatedPost("42").content("{\"content\":\"synthetic-private-comment\"}"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
            .andExpect(status().`is`(status))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(CommentReadController.Response<Void?>(status, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
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
        verify(exactly = 1) { createComment.create(42, 7, "synthetic-private-comment") }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `공개 읽기와 글 404가 있을 때__읽으면__댓글 작성의 인증과 오류 처리 범위가 유지된다`() {
        // given
        every { listComments.read(42) } returns listOf(details(7, "댓글"))
        every { readPost.read(42) } throws PostNotFoundException()

        // when
        val commentsResult = mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
        val postResult = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        commentsResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].commentId").value(12))
        postResult
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(createComment, listPosts)
    }

    companion object {
        @JvmStatic
        fun acceptedContents(): Stream<String> {
            return Stream.of(" ", "　", " \t본문\n ", "긴".repeat(100_000))
        }

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            val expired = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(1))
                .signWith(F3TestInputs.KEY)
                .compact()
            val otherKey = Keys.hmacShaKeyFor(
                "f9-synthetic-other-signing-key-only-012345678901234567890123456789".toByteArray(Charsets.UTF_8),
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
        fun businessFailures(): Stream<Arguments> {
            val failure = IllegalStateException(
                "Bearer synthetic-private-token; synthetic-private-comment",
                SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
            )
            failure.addSuppressed(RuntimeException("https://fixture.invalid/synthetic-private-url"))
            return Stream.of(
                Arguments.of(404, "게시글을 찾을 수 없습니다.", PostNotFoundException()),
                Arguments.of(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", UnsupportedCommentCharactersException(failure)),
                Arguments.of(500, "서버 에러!", failure),
            )
        }

        private fun authenticatedPost(postId: String): MockHttpServletRequestBuilder {
            return post("/api/v1/comments/posts/$postId")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }

        private fun details(authorId: Long, content: String): CommentDetails {
            val created = LocalDateTime.of(2026, 10, 19, 14, 1, 2, 123456789)
            return CommentDetails(12, 42, authorId, "작성자", content, created, created)
        }
    }
}
