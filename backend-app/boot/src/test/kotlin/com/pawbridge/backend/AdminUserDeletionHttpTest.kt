package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var deletion: UserDeletion

    @Test
    fun `검증된 관리자와 위조 회원 헤더__삭제하면__바인딩된 대상만 쓰고 원래 봉투를 그대로 돌려준다`() {
        // given
        justRun { deletion.delete(any()) }

        // when
        val result = mvc.perform(
            delete(PATH + Long.MAX_VALUE)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "8")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "8")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":8}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"회원이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { deletion.delete(Long.MAX_VALUE) }
        confirmVerified(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `서명된 비관리자와 위조 관리자 헤더__잘못된 대상을 삭제하면__바인딩이나 업무 처리 전에 권한을 거부한다`() {
        // given
        val request = delete(PATH + "not-a-number")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Id", "1")
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
        confirmVerified(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun `없거나 잘못된 Bearer__잘못된 대상을 삭제하면__바인딩 전에 거부하고 컨텍스트를 비운다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = delete(PATH + "not-a-number").header("X-User-Role", "ROLE_ADMIN")
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
        confirmVerified(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `승인된 삭제 실패__범위 지정 어드바이스가 처리하면__내부 정보 없이 사용자 상태를 유지한다`(
        failure: RuntimeException,
        status: Int,
        message: String,
    ) {
        // given
        every { deletion.delete(any()) } throws failure

        // when
        val result = mvc.perform(delete(PATH + 8).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))

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
        verify(exactly = 1) { deletion.delete(8) }
        confirmVerified(deletion)
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
    fun `노출되지 않은 이웃 경로나 내부 경로__유효한 관리자가 요청하면__일반 기본 거부를 유지한다`(
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
        confirmVerified(deletion)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `관리자와 잘못된 Long__삭제 전에 바인딩하면__기존 범위 지정 안전 런타임 봉투를 쓴다`() {
        // given
        val request = delete(PATH + "not-a-number")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(deletion)
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
