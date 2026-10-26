package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.comment.application.ListComments
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.application.SearchPosts
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostSearchController
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Date
import java.util.stream.Stream

@WebMvcTest(
    controllers = [UserProfileController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserProfileController::class,
        UserProfileExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        PostSearchController::class,
        PostSearchExceptionHandler::class,
        CommentReadController::class,
        CommentReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserProfileHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var profiles: UserProfileQuery

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @MockkBean
    lateinit var searchPosts: SearchPosts

    @MockkBean
    lateinit var listComments: ListComments

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `서명된 신원과 위조된 헤더·쿼리·본문이 주어졌을 때__내 정보를 조회하면__그 ID의 현재 DB 프로필만 돌려준다`(
        tokenRole: String,
    ) {
        // given
        val profile = profile(7, "현재 닉네임", LocalDateTime.parse("2026-10-19T01:02:03.123456"))

        every { profiles.require(any()) } returns profile

        // when
        val result = mvc.perform(
            get("/api/v1/users/me")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, tokenRole))
                .header("X-User-Id", "999")
                .header("X-User-Email", "spoofed@fixture.invalid")
                .header("X-User-Name", "위조 이름")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"role\":\"ROLE_ADMIN\"}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                      "userId":7,"email":"current-7@fixture.invalid","name":"현재 이름7",
                      "nickname":"현재 닉네임","provider":"GOOGLE","role":"ROLE_SHELTER",
                      "createdAt":"2026-10-19T01:02:03.123456"}}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { profiles.require(7) }
        confirmVerified(profiles, readPost, listPosts, searchPosts, listComments)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  \t ", "닉네임"])
    fun `가입일이 없고 닉네임이 원문일 때__내 정보를 조회하면__널 키·빈 문자열·공백을 일곱 필드로 유지한다`(nickname: String) {
        // given
        every { profiles.require(any()) } returns profile(7, nickname, null)

        // when
        val result = mvc.perform(
            get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(content().string(containsString("\"createdAt\":null")))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(
                        UserProfileController.Response(200, "요청이 성공적으로 처리되었습니다.", profile(7, nickname, null)),
                    ),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { profiles.require(7) }
    }

    @Test
    fun `무관한 subject와 비표준 역할이 주어졌을 때__내 정보를 조회하면__새 정책 없이 기존 신원 계약을 쓴다`() {
        // given
        val token = Jwts.builder()
            .subject("another-member@fixture.invalid")
            .claim("userId", 7)
            .claim("role", "ROLE_FUTURE")
            .claim("name", mapOf("ignored" to true))
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(F3TestInputs.KEY)
            .compact()

        every { profiles.require(any()) } returns profile(7, "현재 닉네임", null)

        // when
        val result = mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer $token"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(7))
            .andExpect(jsonPath("$.data.email").value("current-7@fixture.invalid"))
            .andExpect(jsonPath("$.data.name").value("현재 이름7"))
            .andExpect(jsonPath("$.data.role").value("ROLE_SHELTER"))
        verify(exactly = 1) { profiles.require(7) }
    }

    @Test
    fun `인증된 회원이 더는 없을 때__내 정보를 조회하면__프로필 대체 없이 원래 400을 유지한다`() {
        // given
        every { profiles.require(any()) } throws UserNotFoundException()

        // when
        val result = mvc.perform(
            get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { profiles.require(7) }
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    fun `조회 실패가 주어졌을 때__내 정보를 조회하면__인증 catch 밖에서 안전한 500을 돌려주고 민감 로그를 남기지 않는다`(
        failure: RuntimeException,
    ) {
        // given
        every { profiles.require(any()) } throws failure

        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val result = mvc.perform(
                get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
            )

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
                    "synthetic-private-email",
                    "synthetic-private-name",
                    "synthetic-private-password",
                    "synthetic-private-url",
                    "Bearer ",
                    "jdbc:postgresql:",
                )
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        verify(exactly = 1) { profiles.require(7) }
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
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
                "f12-synthetic-other-signing-key-only-012345678901234567890123456789"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            val forged = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(System.currentTimeMillis() + 3_600_000))
                .signWith(otherKey)
                .compact()
            val refresh = Jwts.builder()
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
        fun queryFailures(): Stream<RuntimeException> {
            return Stream.of(
                DataAccessResourceFailureException("synthetic-private-email", privateFailure()),
                TransactionSystemException("synthetic-private-name", privateFailure()),
                privateFailure(),
            )
        }

        private fun privateFailure(): RuntimeException {
            val failure = IllegalStateException(
                "Bearer synthetic-private-token; synthetic-private-email; synthetic-private-name",
                SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
            )
            failure.addSuppressed(RuntimeException("https://fixture.invalid/synthetic-private-url"))
            return failure
        }

        private fun profile(id: Long, nickname: String, created: LocalDateTime?): UserProfile {
            return UserProfile(
                id, "current-$id@fixture.invalid", "현재 이름$id", nickname, "GOOGLE", "ROLE_SHELTER", created,
            )
        }
    }
}
