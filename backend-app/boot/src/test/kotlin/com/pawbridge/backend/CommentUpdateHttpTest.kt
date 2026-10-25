package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CommentDetails
import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentReader
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import io.mockk.verifySequence
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional
import java.util.stream.Stream

@WebMvcTest(
    controllers = [CommentUpdateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        CommentUpdateController::class,
        CommentUpdateExceptionHandler::class,
        UpdateComment::class,
        CommentCreateController::class,
        CommentCreateExceptionHandler::class,
        CommentDeleteController::class,
        CommentDeleteExceptionHandler::class,
        CommentReadController::class,
        CommentReadExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class CommentUpdateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var comments: CommentReader

    @MockkBean
    lateinit var modification: CommentModification

    @MockkBean
    lateinit var users: UserNicknameQuery

    @MockkBean
    lateinit var createComment: CreateComment

    @MockkBean
    lateinit var deletion: CommentDeletion

    @MockkBean
    lateinit var listComments: ListComments

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `검증된 소유자와 위조 신원일 때__댓글을 수정하면__새 내용에 원본 일곱 필드와 이전 날짜를 돌려준다`(
        role: String,
    ) {
        // given
        every { comments.findById(12) } returns Optional.of(snapshot(7, "원본"))
        every { users.findNickname(7) } returns Optional.of("작성자")
        justRun { modification.update(12, "  수정 본문  ") }

        // when
        val result = mvc.perform(
            put("/api/v1/comments/12")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .content("{\"content\":\"  수정 본문  \",\"authorId\":999,\"postId\":999,\"commentId\":999}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(
                        CommentUpdateController.Response(200, "댓글이 수정되었습니다.", details(7, "  수정 본문  ", "작성자")),
                    ),
                    JsonCompareMode.STRICT,
                ),
            )
        verifySequence {
            comments.findById(12)
            users.findNickname(7)
            modification.update(12, "  수정 본문  ")
        }
        confirmVerified(createComment, deletion, listComments, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("acceptedContents")
    fun `비공백 내용일 때__댓글을 수정하면__공백과 원문을 보존하고 최대 길이를 두지 않는다`(
        content: String,
    ) {
        // given
        every { comments.findById(12) } returns Optional.of(snapshot(7, "원본"))
        every { users.findNickname(7) } returns Optional.of("작성자")
        justRun { modification.update(12, content) }

        // when
        val result = mvc.perform(authenticatedPut("12").content(mapper.writeValueAsString(mapOf("content" to content))))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").value(content))
            .andExpect(jsonPath("$.data.updatedAt").value(UPDATED.toString()))
        verify(exactly = 1) { modification.update(12, content) }
    }

    @Test
    fun `내용이 같을 때__댓글을 수정하면__닉네임을 조회하고 수정 없이 이전 날짜를 돌려준다`() {
        // given
        every { comments.findById(12) } returns Optional.of(snapshot(7, "원본"))
        every { users.findNickname(7) } returns Optional.of("작성자")

        // when
        val result = mvc.perform(authenticatedPut("12").content("{\"content\":\"원본\"}"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(
                        CommentUpdateController.Response(200, "댓글이 수정되었습니다.", details(7, "원본", "작성자")),
                    ),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { comments.findById(12) }
        verify(exactly = 1) { users.findNickname(7) }
        confirmVerified(modification)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \"}", "{\"content\":\" \\t\\r\\n \"}"])
    fun `내용이 없거나 null이거나 ASCII 공백일 때__댓글을 수정하면__소유권과 닉네임 조회 전에 검증한다`(
        body: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedPut("12").content(body))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"content: 내용은 필수입니다\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
    }

    @Test
    fun `저장된 내용이 공백일 때__공백으로 수정하면__MVC 검증이 변경 없음으로 우회되지 않는다`() {
        // given
        every { comments.findById(12) } returns Optional.of(snapshot(7, " "))

        // when
        val result = mvc.perform(authenticatedPut("12").content("{\"content\":\" \"}"))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("content: 내용은 필수입니다"))
        confirmVerified(comments, users, modification)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "null", "{", "[]"])
    fun `본문이 없거나 JSON이 깨졌을 때__댓글을 수정하면__업무 호출 전에 안전한 400을 돌려준다`(
        body: String,
    ) {
        // given

        // when
        val result = mvc.perform(authenticatedPut("12").content(body))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun `토큰이 없거나 믿을 수 없고 요청도 잘못됐을 때__댓글을 수정하면__ID와 JSON 바인딩 전에 인증부터 거부한다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = put("/api/v1/comments/not-a-number")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{")
            .header("X-User-Id", "7")
            .header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    mapper.writeValueAsString(CommentUpdateController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    fun `서명됐지만 잘못된 신원일 때__댓글을 수정하면__바인딩과 업무 호출 전에 거부한다`(
        claims: Map<String, Any>,
    ) {
        // given
        val token = Jwts.builder()
            .claims(claims)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()

        // when
        val result = mvc.perform(
            put("/api/v1/comments/not-a-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")
                .header("Authorization", "Bearer $token"),
        )

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json("{\"code\":401,\"message\":\"토큰 파싱에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
    }

    @Test
    fun `소유자 둘에 이어 익명 요청이 올 때__댓글을 수정하면__이전 신원을 지운다`() {
        // given
        every { comments.findById(12) } returnsMany listOf(
            Optional.of(snapshot(7, "원본")),
            Optional.of(snapshot(8, "원본")),
        )
        every { users.findNickname(7) } returns Optional.of("작성자")
        every { users.findNickname(8) } returns Optional.of("다른 작성자")

        // when
        val ownerResults = longArrayOf(7, 8).map { userId ->
            userId to mvc.perform(
                put("/api/v1/comments/12")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"원본\"}")
                    .header("Authorization", "Bearer " + F3TestInputs.token(userId, "ROLE_USER")),
            )
        }
        val anonymousResult = mvc.perform(
            put("/api/v1/comments/12").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"원본\"}"),
        )

        // then
        for ((userId, result) in ownerResults) {
            result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorId").value(userId.toInt()))
        }
        anonymousResult.andExpect(status().isUnauthorized())
        verify(exactly = 2) { comments.findById(12) }
        verify(exactly = 1) { users.findNickname(7) }
        verify(exactly = 1) { users.findNickname(8) }
        confirmVerified(users, comments)
        confirmVerified(modification)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(longs = [12, 0, -1])
    fun `활성 댓글이 없을 때__수정하면__새 ID 검증 없이 닉네임과 쓰기 전에 404를 돌려준다`(
        id: Long,
    ) {
        // given
        every { comments.findById(id) } returns Optional.empty()

        // when
        val result = mvc.perform(authenticatedPut(id.toString()).content("{\"content\":\"수정\"}"))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"댓글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { comments.findById(id) }
        confirmVerified(users, modification)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `다른 작성자의 댓글일 때__어떤 역할이든 수정하면__관리자 예외 없이 닉네임과 쓰기 전에 403을 돌려준다`(
        role: String,
    ) {
        // given
        every { comments.findById(12) } returns Optional.of(snapshot(8, "원본"))

        // when
        val result = mvc.perform(
            put("/api/v1/comments/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정\"}")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(
                content().json(
                    "{\"code\":403,\"message\":\"댓글에 접근할 권한이 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { comments.findById(12) }
        confirmVerified(users, modification)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "9223372036854775808"])
    fun `파싱할 수 없는 댓글 ID와 유효한 신원일 때__수정하면__기존 안전한 500을 유지한다`(id: String) {
        // given

        // when
        val result = mvc.perform(authenticatedPut(id).content("{\"content\":\"수정\"}"))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(comments, users, modification)
    }

    @ParameterizedTest
    @MethodSource("updateFailures")
    fun `조회나 닉네임이나 쓰기가 실패할 때__수정하면__안전한 오류 계약을 지키고 민감 로그를 남기지 않는다`(
        stage: Int,
        expectedStatus: Int,
        message: String,
    ) {
        // given
        val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
        val failure = IllegalStateException("Bearer synthetic-private-token; synthetic-private-comment", cause)
        failure.addSuppressed(RuntimeException("https://fixture.invalid/synthetic-private-url"))
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        if (stage >= 1) {
            every { comments.findById(12) } returns Optional.of(snapshot(7, "원본"))
        }
        if (stage >= 2) {
            every { users.findNickname(7) } returns Optional.of("작성자")
        }
        when (stage) {
            0 -> every { comments.findById(12) } throws failure
            1 -> every { users.findNickname(7) } throws failure
            2 -> every { modification.update(12, "synthetic-private-comment") } throws
                UnsupportedCommentCharactersException(failure)
            3 -> every { modification.update(12, "synthetic-private-comment") } throws failure
            else -> throw AssertionError("Unknown synthetic failure stage")
        }

        // when
        val result = try {
            mvc.perform(authenticatedPut("12").content("{\"content\":\"synthetic-private-comment\"}"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
            .andExpect(status().`is`(expectedStatus))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(CommentUpdateController.Response<Void?>(expectedStatus, message, null)),
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
        verify(exactly = 1) { comments.findById(12) }
        if (stage == 0) {
            confirmVerified(users, modification)
        } else {
            verify(exactly = 1) { users.findNickname(7) }
            if (stage == 1) {
                confirmVerified(modification)
            } else {
                verify(exactly = 1) { modification.update(12, "synthetic-private-comment") }
            }
        }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "unavailable", "empty", "space"])
    fun `닉네임 조회 결과가 다양할 때__같은 내용으로 수정하면__대체값과 null과 공백을 추가 필드 없이 직렬화한다`(
        kind: String,
    ) {
        // given
        val nickname: String?
        every { comments.findById(12) } returns Optional.of(snapshot(7, "원본"))
        when (kind) {
            "missing" -> {
                every { users.findNickname(7) } returns Optional.empty()
                nickname = "사용자7"
            }
            "unavailable" -> {
                every { users.findNickname(7) } throws
                    UserNicknameUnavailableException(IllegalStateException("synthetic unavailable"))
                nickname = "사용자7"
            }
            "empty" -> {
                every { users.findNickname(7) } returns Optional.of("")
                nickname = null
            }
            "space" -> {
                every { users.findNickname(7) } returns Optional.of(" ")
                nickname = " "
            }
            else -> throw AssertionError("Unknown synthetic nickname result")
        }

        // when
        val result = mvc.perform(authenticatedPut("12").content("{\"content\":\"원본\"}"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(
                        CommentUpdateController.Response(200, "댓글이 수정되었습니다.", details(7, "원본", nickname)),
                    ),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(modification)
    }

    @Test
    fun `기존 댓글 읽기와 작성과 삭제와 글 404가 있을 때__요청하면__수정의 오류 처리와 인증 범위가 유지된다`() {
        // given
        val comment = details(7, "댓글", "작성자")

        every { listComments.read(42) } returns listOf(comment)
        every { createComment.create(42, 7, "댓글") } returns comment
        justRun { deletion.delete(12, 7) }
        every { readPost.read(42) } throws PostNotFoundException()

        // when
        val readResult = mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
        val createResult = mvc.perform(
            post("/api/v1/comments/posts/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"댓글\"}")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val deleteResult = mvc.perform(
            delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val postResult = mvc.perform(get("/api/v1/posts/read/42"))

        // then
        readResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].commentId").value(12))
        createResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."))
        deleteResult
            .andExpect(status().isOk())
            .andExpect(
                content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        postResult
            .andExpect(status().isNotFound())
            .andExpect(
                content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(comments, users, modification, listPosts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/comments/12", "POST /api/v1/comments/12", "PATCH /api/v1/comments/12",
            "HEAD /api/v1/comments/12", "OPTIONS /api/v1/comments/12", "PUT /api/v1/comments", "PUT /api/v1/comments/",
            "PUT /api/v1/comments/12/", "PUT /api/v1/comments/12/extra", "PUT /api/v1/comments/posts/42",
            "PUT /api/v1/comments/posts/read/42", "PUT /api/comments/12", "PUT /api/v1/admin/comments/12",
        ],
    )
    fun `노출되지 않은 메서드나 인접 경로일 때__유효한 토큰으로 요청하면__업무 호출 없이 거부한다`(
        request: String,
    ) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42",
            "POST /api/v1/comments/posts/42", "DELETE /api/v1/comments/12",
        ],
    )
    fun `토큰 없는 기존 쓰기일 때__요청하면__JWT 인증을 유지한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
        confirmVerified(comments, users, modification, createComment, deletion, listComments, readPost, listPosts)
    }

    companion object {
        private val CREATED: LocalDateTime = LocalDateTime.parse("2026-10-18T11:01:02.123456789")
        private val UPDATED: LocalDateTime = LocalDateTime.parse("2026-10-19T12:03:04.987654321")

        @JvmStatic
        fun acceptedContents(): Stream<String> {
            return Stream.of(" ", "　", " \t수정 본문\n ", "긴".repeat(100_000))
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
                "f11-synthetic-other-signing-key-only-012345678901234567890123456789".toByteArray(Charsets.UTF_8),
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
        fun updateFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(0, 500, "서버 에러!"),
                Arguments.of(1, 500, "서버 에러!"),
                Arguments.of(2, 400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."),
                Arguments.of(3, 500, "서버 에러!"),
            )
        }

        private fun authenticatedPut(id: String): MockHttpServletRequestBuilder {
            return put("/api/v1/comments/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }

        private fun snapshot(author: Long, content: String): CommentSnapshot {
            return CommentSnapshot(12, 42, author, content, CREATED, UPDATED)
        }

        private fun details(author: Long, content: String, nickname: String?): CommentDetails {
            return CommentDetails(12, 42, author, nickname, content, CREATED, UPDATED)
        }
    }
}
