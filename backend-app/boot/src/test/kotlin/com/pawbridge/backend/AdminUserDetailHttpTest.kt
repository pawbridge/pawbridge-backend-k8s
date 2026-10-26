package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.stream.Stream

@WebMvcTest(
    controllers = [AdminUserDetailController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminUserDetailController::class,
        UserProfileExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminUserDetailHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var profiles: UserProfileQuery

    @Test
    fun `관리자와 위조 신원__다른 회원을 조회하면__경로만 바인딩하고 정확히 일곱 필드를 재사용한다`() {
        // given
        every { profiles.require(any()) } returns UserProfile(
            Long.MAX_VALUE,
            "member@fixture.invalid",
            "Synthetic name",
            "Synthetic nickname",
            "GOOGLE",
            "ROLE_USER",
            LocalDateTime.parse("2026-10-19T01:02:03.123456"),
        )

        // when
        val result = mvc.perform(
            get(PATH + Long.MAX_VALUE)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "8")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "8"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                      "userId":9223372036854775807,"email":"member@fixture.invalid","name":"Synthetic name",
                      "nickname":"Synthetic nickname","provider":"GOOGLE","role":"ROLE_USER",
                      "createdAt":"2026-10-19T01:02:03.123456"}}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { profiles.require(Long.MAX_VALUE) }
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `서명된 사용자와 위조 관리자 헤더__잘못된 대상을 조회하면__바인딩이나 조회 전에 거부한다`() {
        // given
        val request = get(PATH + "not-a-number")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Role", "ROLE_ADMIN")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun `없거나 잘못된 Bearer__잘못된 대상을 조회하면__조회 전에 거부하고 컨텍스트를 비운다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get(PATH + "not-a-number").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `없는 회원 또는 조회 실패__관리자가 조회하면__기존 안전한 사용자 어드바이스를 재사용한다`(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        // given
        every { profiles.require(any()) } throws failure

        // when
        val result = mvc.perform(get(PATH + 8).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))

        // then
        result
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { profiles.require(8) }
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/admin/users/search/extra",
            "GET /api/v1/admin/users/extra/extra",
            "PUT /api/v1/admin/users/8/extra",
        ],
    )
    fun `이식되지 않은 예약 컬렉션이나 메서드__관리자가 요청하면__일반 기본 거부를 유지한다`(
        wire: String,
    ) {
        // given
        val parts = wire.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `관리자와 잘못된 Long__조회 전에 바인딩하면__서비스 호출 없이 400을 응답한다`() {
        // given
        val request = get(PATH + "not-a-number")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":400,\"message\":\"요청 경로의 식별자 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    companion object {
        private const val PATH = "/api/v1/admin/users/"
        private const val CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun rejectedTokens(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(UserNotFoundException(), 400, "존재하지 않는 회원입니다."),
                Arguments.of(
                    DataAccessResourceFailureException("synthetic-private-query", SQLException("synthetic-private-password")),
                    500,
                    "서버 에러!",
                ),
            )
        }
    }
}
