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
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
