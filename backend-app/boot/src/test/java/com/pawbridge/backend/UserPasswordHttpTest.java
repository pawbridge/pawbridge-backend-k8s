package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword;
import com.pawbridge.backend.user.internal.password.application.InvalidCurrentPasswordException;
import com.pawbridge.backend.user.internal.password.application.OAuthUserCannotChangePasswordException;
import com.pawbridge.backend.user.internal.password.application.SamePasswordException;
import com.pawbridge.backend.user.internal.password.web.UserPasswordController;
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import io.jsonwebtoken.Jwts;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UserPasswordController.class, properties = "pawbridge.user-password.enabled=true",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {UserPasswordController.class, UserPasswordExceptionHandler.class,
        UserNicknameController.class, UserNicknameExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserPasswordHttpTest {
    private static final String CURRENT_REQUIRED = "currentPassword: 현재 비밀번호는 필수입니다.";
    private static final String NEW_REQUIRED = "newPassword: 새 비밀번호는 필수입니다.";
    private static final String NEW_PATTERN = "newPassword: 비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다.";
    private static final String VALID = "{\"currentPassword\":\"Current1!\",\"newPassword\":\"NewPass1!\"}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChangeUserPassword change;
    @MockitoBean ChangeUserNickname nicknames;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__verified_principal_and_spoofed_identity__when__changing_password__then__use_only_principal_and_return_original_success(String role) throws Exception {
        mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN").param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Current1!\",\"newPassword\":\"NewPass1!\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"비밀번호가 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(change).change(7, "Current1!", "NewPass1!");
        verifyNoMoreInteractions(change);
        verifyNoInteractions(nicknames);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcdef1!", "Abcdefghijklmnopqr1@", "a1@$!%*#?&a1@$!%*#?&"})
    void given__new_password_at_original_pattern_boundaries__when__changing__then__forward_raw_values(String newPassword) throws Exception {
        mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("currentPassword", " any current ", "newPassword", newPassword))))
                .andExpect(status().isOk());
        verify(change).change(7, " any current ", newPassword);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void given__invalid_or_absent_fields__when__changing__then__validation_precedes_service_with_original_field_messages(String json, List<String> messages) throws Exception {
        var result = mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400)).andReturn();
        var response = mapper.readTree(result.getResponse().getContentAsString());
        // Constraint messages contain ", " themselves, so compare against every join order.
        String message = response.get("message").asText();
        assertThat(message).isIn(messages.size() == 1 ? List.of(messages.get(0))
                : List.of(messages.get(0) + ", " + messages.get(1), messages.get(1) + ", " + messages.get(0)));
        assertThat(response.get("data").isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Secret").doesNotContain("short1!");
        verifyNoInteractions(change, nicknames);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("{}", List.of(CURRENT_REQUIRED, NEW_REQUIRED)),
                Arguments.of("{\"currentPassword\":null,\"newPassword\":\"NewPass1!\"}", List.of(CURRENT_REQUIRED)),
                Arguments.of("{\"currentPassword\":\"   \",\"newPassword\":\"NewPass1!\"}", List.of(CURRENT_REQUIRED)),
                Arguments.of("{\"currentPassword\":\"Secret1!\"}", List.of(NEW_REQUIRED)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"\"}", List.of(NEW_REQUIRED, NEW_PATTERN)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"short1!\"}", List.of(NEW_PATTERN)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"NoDigits!!\"}", List.of(NEW_PATTERN)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"NoSpecial12\"}", List.of(NEW_PATTERN)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"Space 12!x\"}", List.of(NEW_PATTERN)),
                Arguments.of("{\"currentPassword\":\"Secret1!\",\"newPassword\":\"Abcdefghijklmnopqr1@x\"}", List.of(NEW_PATTERN)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{\"currentPassword\":[],\"newPassword\":\"NewPass1!\"}"})
    void given__malformed_or_nonobject_json__when__changing__then__return_original_safe_body_error_before_service(String json) throws Exception {
        mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(change, nicknames);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token__when__changing__then__authentication_precedes_validation_and_service(String authorization, String message) throws Exception {
        var request = put("/api/v1/users/me/password").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{");
        if (authorization != null) request.header("Authorization", authorization);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new UserPasswordController.Response<>(401, message, null)), JsonCompareMode.STRICT));
        verifyNoInteractions(change, nicknames);
    }

    static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER").expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."), Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + expired, "유효하지 않거나 만료된 토큰입니다."));
    }

    @ParameterizedTest
    @MethodSource("serviceFailures")
    void given__service_rejection__when__changing__then__map_to_original_status_and_message(RuntimeException failure, int status, String message) throws Exception {
        doThrow(failure).when(change).change(7, "Current1!", "NewPass1!");
        mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().is(status))
                .andExpect(content().json(mapper.writeValueAsString(new UserPasswordController.Response<>(status, message, null)), JsonCompareMode.STRICT));
        verify(change).change(7, "Current1!", "NewPass1!");
    }

    static Stream<Arguments> serviceFailures() {
        return Stream.of(Arguments.of(new UserNotFoundException(), 400, "존재하지 않는 회원입니다."),
                Arguments.of(new OAuthUserCannotChangePasswordException(), 400, "소셜 로그인 사용자는 비밀번호를 변경할 수 없습니다."),
                Arguments.of(new InvalidCurrentPasswordException(), 400, "현재 비밀번호가 일치하지 않습니다."),
                Arguments.of(new SamePasswordException(), 400, "현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다."),
                Arguments.of(new DataAccessResourceFailureException("synthetic private database failure"), 500, "서버 에러!"),
                Arguments.of(new IllegalStateException("synthetic private failure"), 500, "서버 에러!"));
    }

    @Test
    void given__existing_nickname_route__when__password_feature_is_enabled__then__password_advice_does_not_intercept_it() throws Exception {
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("닉네임이 변경되었습니다."));
        verify(nicknames).change(7, "새닉네임");
        verifyNoInteractions(change);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/users/me/password", "POST /api/v1/users/me/password",
            "PATCH /api/v1/users/me/password", "DELETE /api/v1/users/me/password",
            "PUT /api/v1/users/me/password/", "PUT /api/v1/users/me/password/extra",
            "PUT /api/v1/users/7/password", "PUT /api/users/me/password"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_service(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(change, nicknames);
    }
}
