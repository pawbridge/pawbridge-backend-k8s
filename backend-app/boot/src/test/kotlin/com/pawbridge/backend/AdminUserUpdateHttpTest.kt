package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [AdminUserUpdateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminUserUpdateController::class,
        AdminUserUpdateExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminUserUpdateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var members: AdminUserUpdate

    @Test
    fun given__signed_admin_and_numeric_nickname__when__updating_another_target__then__bind_only_path_and_original_fields_and_envelope() {
        mvc.perform(
            put(PATH + Long.MAX_VALUE)
                .header("Authorization", admin())
                .header("X-User-Id", "9")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "9")
                .contentType("application/json")
                .content("{\"nickname\":\"123\",\"role\":\"ROLE_ADMIN\",\"careRegNo\":\"A\",\"userId\":9}"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"회원 정보가 수정되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(members).update(Long.MAX_VALUE, Changes("123", Role.ROLE_ADMIN, "A"))
        verifyNoMoreInteractions(members)
        cleared()
    }

    @Test
    fun given__null_patch_fields__when__admin_updates__then__preserve_optional_source_request_without_new_required_fields() {
        mvc.perform(put(PATH + 0).header("Authorization", admin()).contentType("application/json").content("{}"))
            .andExpect(status().isOk())
        verify(members).update(0, Changes(null, null, null))
        verifyNoMoreInteractions(members)
        cleared()
    }

    @Test
    fun given__signed_user_with_spoofed_admin_and_bad_body__when__updating__then__authorize_before_binding_or_business() {
        mvc.perform(
            put(PATH + "bad")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType("application/json")
                .content("bad"),
        )
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(members)
        cleared()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["Bearer invalid"])
    fun given__missing_or_invalid_token__when__updating_malformed_input__then__reject_before_binding(token: String?) {
        val request = put(PATH + "bad").header("X-User-Role", "ROLE_ADMIN").contentType("application/json").content("bad")
        if (token != null) {
            request.header("Authorization", token)
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "x", "12345678901", "emoji🐾"])
    fun given__nickname_outside_admin_specific_pattern__when__validating__then__return_original_400_before_business(
        nickname: String,
    ) {
        val json = ObjectMapper().writeValueAsString(mapOf("nickname" to nickname))
        mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content(json))
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"nickname: 닉네임은 2~10자의 영문, 숫자, 한글만 가능합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "{\"role\":\"role_user\"}", "{\"role\":\"ROLE_UNKNOWN\"}"])
    fun given__malformed_json_or_unknown_role__when__reading_body__then__return_original_unreadable_400(body: String) {
        mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content(body))
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(members)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__missing_approval_duplicate_database_or_commit_failure__when__handling__then__preserve_original_status_and_hide_private_details(
        failure: RuntimeException,
        code: Int,
        message: String,
    ) {
        doThrow(failure).`when`(members).update(8, Changes(null, null, null))
        mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content("{}"))
            .andExpect(status().`is`(code))
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(
                content().json(
                    ObjectMapper().writeValueAsString(UserNicknameController.Response<Void?>(code, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(members).update(8, Changes(null, null, null))
        verifyNoMoreInteractions(members)
        cleared()
    }

    @Test
    fun given__admin_with_bad_path_long__when__binding__then__retain_original_runtime_500_before_business() {
        mvc.perform(put(PATH + "bad").header("Authorization", admin()).contentType("application/json").content("{}"))
            .andExpect(status().isInternalServerError())
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT /api/v1/admin/users/8/extra", "PUT /api/v1/admin/users", "POST /api/v1/admin/users/8"])
    fun given__an_unported_neighbor_or_method__when__admin_requests__then__keep_default_deny(wire: String) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", admin()))
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(members)
        cleared()
    }

    companion object {
        private const val PATH = "/api/v1/admin/users/"

        @JvmStatic
        fun failures(): Stream<Arguments> {
            val charset = PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C23514\u0000spawbridge_user\u0000nck_rollback_charset_users_nickname\u0000Msynthetic-private\u0000",
                ),
            )
            return Stream.of(
                Arguments.of(UserNotFoundException(), 400, "존재하지 않는 회원입니다."),
                Arguments.of(ShelterApprovalRequiredException(), 400, "보호소 권한은 담당자 신청 승인으로 부여해야 합니다."),
                Arguments.of(NicknameDuplicateException(), 409, "이미 사용 중인 닉네임입니다."),
                Arguments.of(DataIntegrityViolationException("synthetic unique race"), 500, "서버 에러!"),
                Arguments.of(TransactionSystemException("synthetic-private-commit"), 500, "서버 에러!"),
                Arguments.of(
                    DataIntegrityViolationException("synthetic wrapper", charset),
                    400,
                    "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.",
                ),
            )
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
