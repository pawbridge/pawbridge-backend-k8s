package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var profiles: UserProfileQuery

    @Test
    fun given__admin_and_spoofed_identity__when__reading_another_member__then__bind_only_the_path_and_reuse_exact_seven_fields() {
        whenever(profiles.require(Long.MAX_VALUE)).thenReturn(
            UserProfile(
                Long.MAX_VALUE,
                "member@fixture.invalid",
                "Synthetic name",
                "Synthetic nickname",
                "GOOGLE",
                "ROLE_USER",
                LocalDateTime.parse("2026-10-19T01:02:03.123456"),
            ),
        )

        mvc.perform(
            get(PATH + Long.MAX_VALUE)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "8")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "8"),
        )
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
        verify(profiles).require(Long.MAX_VALUE)
        verifyNoMoreInteractions(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__signed_user_and_spoofed_admin_header__when__reading_a_malformed_target__then__deny_before_binding_or_query() {
        mvc.perform(
            get(PATH + "not-a-number")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun given__missing_or_invalid_bearer__when__reading_a_malformed_target__then__reject_before_query_and_clear_context(
        authorization: String?,
        message: String,
    ) {
        val request = get(PATH + "not-a-number").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__missing_member_or_query_failure__when__admin_reads__then__reuse_existing_safe_user_advice(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        whenever(profiles.require(8)).thenThrow(failure)
        mvc.perform(get(PATH + 8).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(profiles).require(8)
        verifyNoMoreInteractions(profiles)
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
    fun given__an_unported_reserved_collection_or_method__when__admin_requests_it__then__keep_generic_default_deny(
        wire: String,
    ) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(profiles)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__admin_with_malformed_long__when__binding_before_query__then__retain_original_runtime_error_envelope() {
        mvc.perform(get(PATH + "not-a-number").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(profiles)
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
