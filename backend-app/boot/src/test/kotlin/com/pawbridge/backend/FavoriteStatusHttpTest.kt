package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
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
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
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
import org.springframework.transaction.TransactionSystemException
import java.sql.SQLException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [FavoriteStatusController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        FavoriteStatusController::class,
        FavoriteStatusExceptionHandler::class,
        UserProfileController::class,
        UserProfileExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class FavoriteStatusHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var favorites: FavoriteStatusQuery

    @MockkBean
    lateinit var profiles: UserProfileQuery

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `검증된 주체와 위조된 ID일 때__즐겨찾기를 확인하면__주체와 경로 ID를 쓰고 정확한 불리언 봉투를 돌려준다`(
        exists: Boolean,
    ) {
        // given
        every { favorites.isFavorite(7, 42) } returns exists

        // when
        val result = mvc.perform(
            get("/api/v1/favorites/42/check")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("animalId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"animalId\":999}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":" + exists + "}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { favorites.isFavorite(7, 42) }
        confirmVerified(favorites, profiles, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, Long.MIN_VALUE, Long.MAX_VALUE])
    fun `0이나 음수나 Long 경계의 동물 ID일 때__확인하면__새 양수 규칙 없이 원래 Long이 조회에 닿는다`(
        animalId: Long,
    ) {
        // given
        every { favorites.isFavorite(7, animalId) } returns false

        // when
        val result = mvc.perform(
            get("/api/v1/favorites/{animalId}/check", animalId)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":false}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { favorites.isFavorite(7, animalId) }
        confirmVerified(profiles, readPost, listPosts)
    }

    @Test
    fun `주체 둘에 이어 익명 요청이 올 때__확인하면__각자 자기 신원을 쓰고 익명은 재사용할 수 없다`() {
        // given
        every { favorites.isFavorite(7, 42) } returns true
        every { favorites.isFavorite(8, 42) } returns false

        // when
        val principalResults = longArrayOf(7, 8).map { id ->
            id to mvc.perform(
                get("/api/v1/favorites/42/check").header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")),
            )
        }
        val anonymousResult = mvc.perform(get("/api/v1/favorites/42/check"))

        // then
        for ((id, result) in principalResults) {
            result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(id == 7L))
        }
        anonymousResult
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json("{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { favorites.isFavorite(7, 42) }
        verify(exactly = 1) { favorites.isFavorite(8, 42) }
        confirmVerified(favorites)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun `토큰이 없거나 잘못되고 ID도 깨졌을 때__확인하면__Long 변환과 조회 전에 인증부터 거부한다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get("/api/v1/favorites/abc/check").header("X-User-Id", "7")
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
                    mapper.writeValueAsString(FavoriteStatusController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(favorites, profiles, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "9223372036854775808", "-9223372036854775809"])
    fun `Long이 아니거나 넘치는 ID일 때__인증된 채 확인하면__조회 전에 기존 런타임 500을 유지한다`(
        animalId: String,
    ) {
        // given

        // when
        val result = mvc.perform(
            get("/api/v1/favorites/{animalId}/check", animalId)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(favorites, profiles, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    fun `SQL이나 트랜잭션 실패일 때__확인하면__false나 인증 실패가 아닌 안전한 500을 주고 비밀을 남기지 않는다`(
        failure: RuntimeException,
    ) {
        // given
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        every { favorites.isFavorite(7, 42) } throws failure

        // when
        val result = try {
            mvc.perform(
                get("/api/v1/favorites/42/check").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
            )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        assertThat(appender.list).isNotEmpty()
        assertThat(appender.list).allSatisfy { event ->
            assertThat(event.throwableProxy).isNull()
            assertThat(event.formattedMessage).doesNotContain("synthetic-private", "Bearer ", "jdbc:")
        }
        verify(exactly = 1) { favorites.isFavorite(7, 42) }
        confirmVerified(favorites)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `프로필이 없고 공개 글 목록이 있을 때__요청하면__즐겨찾기 오류 처리와 인증이 각자 계약을 유지한다`() {
        // given
        every { profiles.require(7) } throws UserNotFoundException()
        every { listPosts.read() } returns listOf()

        // when
        val profileResult = mvc.perform(
            get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val postsResult = mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer invalid"))

        // then
        profileResult
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        postsResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
        confirmVerified(favorites)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/favorites", "GET /api/v1/favorites/42", "GET /api/v1/favorites/42/check/",
            "GET /api/v1/favorites/42/check/extra", "GET /api/favorites/42/check", "GET /api/v1/favorites/internal/42/check",
            "HEAD /api/v1/favorites/42/check", "OPTIONS /api/v1/favorites/42/check", "POST /api/v1/favorites/42/check",
            "PUT /api/v1/favorites/42/check", "POST /api/v1/favorites/42", "DELETE /api/v1/favorites/42",
        ],
    )
    fun `노출되지 않은 메서드나 인접 경로일 때__유효한 토큰으로 요청하면__즐겨찾기 조회 없이 거부한다`(
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
        confirmVerified(favorites, profiles, readPost, listPosts)
    }

    companion object {
        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun queryFailures(): Stream<RuntimeException> {
            val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
            return Stream.of(
                DataAccessResourceFailureException("synthetic-private-query", cause),
                TransactionSystemException("Bearer synthetic-private-token", cause),
            )
        }
    }
}
