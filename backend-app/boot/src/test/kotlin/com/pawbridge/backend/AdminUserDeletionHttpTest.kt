package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [AdminUserDeletionController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminUserDeletionController::class,
        AdminUserDeletionExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminUserDeletionHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var deletion: UserDeletion

    @Test
    fun given__verified_admin_and_spoofed_member_headers__when__deleting__then__use_only_the_bound_target_and_exact_original_envelope() {
        mvc.perform(
            delete(PATH + Long.MAX_VALUE)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "8")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "8")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":8}"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"회원이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(deletion).delete(Long.MAX_VALUE)
        verifyNoMoreInteractions(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__signed_nonadmin_with_spoofed_admin_header__when__deleting_an_invalid_target__then__authorize_before_binding_or_business() {
        mvc.perform(
            delete(PATH + "not-a-number")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "1")
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun given__missing_or_invalid_bearer__when__deleting_a_malformed_target__then__reject_before_binding_and_clear_context(
        authorization: String?,
        message: String,
    ) {
        val request = delete(PATH + "not-a-number").header("X-User-Role", "ROLE_ADMIN")
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
        verifyNoInteractions(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__authorized_deletion_failure__when__the_scoped_advice_handles_it__then__preserve_user_status_without_private_details(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        doThrow(failure).`when`(deletion).delete(8)
        mvc.perform(delete(PATH + 8).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(deletion).delete(8)
        verifyNoMoreInteractions(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/admin/users/8/extra",
            "PUT /api/v1/admin/users/8/extra",
            "DELETE /api/v1/admin/users/8/extra",
            "DELETE /internal/private-notes/members/8",
        ],
    )
    fun given__an_unexposed_neighbor_or_internal_route__when__a_valid_admin_requests_it__then__keep_generic_default_deny(
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
        verifyNoInteractions(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__an_admin_and_malformed_long__when__binding_before_deletion__then__use_the_existing_scoped_safe_runtime_envelope() {
        mvc.perform(
            delete(PATH + "not-a-number").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(deletion)
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
                Arguments.of(MemberCleanupUnavailableException(), 503, "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요."),
                Arguments.of(
                    DataAccessResourceFailureException("synthetic-private-query", SQLException("synthetic-private-password")),
                    500,
                    "서버 에러!",
                ),
            )
        }
    }
}
