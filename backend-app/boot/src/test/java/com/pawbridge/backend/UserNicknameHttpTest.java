package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UserNicknameController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {UserNicknameController.class, UserNicknameExceptionHandler.class,
        UserProfileController.class, UserProfileExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserNicknameHttpTest {
    private static final String REQUIRED = "닉네임은 필수입니다.";
    private static final String SIZE = "닉네임은 2~30자여야 합니다.";
    private static final String PATTERN = "닉네임은 한글, 영문, 숫자를 사용할 수 있으며 띄워쓰기가 불가능합니다. 한글 또는 영문을 최소 1자 포함해야 합니다.";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChangeUserNickname change;
    @MockitoBean UserProfileQuery profiles;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__verified_principal_and_spoofed_identity__when__changing_nickname__then__use_only_principal_and_return_original_null_success(String role) throws Exception {
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN").param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"새닉네임7\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"닉네임이 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(change).change(7, "새닉네임7");
        verifyNoMoreInteractions(change);
        verifyNoInteractions(profiles, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"가1", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcd"})
    void given__valid_two_or_thirty_character_nickname__when__changing__then__forward_original_text(String nickname) throws Exception {
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("nickname", nickname))))
                .andExpect(status().isOk());
        verify(change).change(7, nickname);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void given__json_boolean_nickname__when__changing__then__preserve_existing_jackson_string_coercion(boolean nickname) throws Exception {
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":" + nickname + "}"))
                .andExpect(status().isOk());
        verify(change).change(7, Boolean.toString(nickname));
    }

    @ParameterizedTest
    @MethodSource("invalidNicknames")
    void given__invalid_or_absent_nickname__when__changing__then__validation_precedes_service_and_keeps_original_message_set(String json, List<String> messages) throws Exception {
        var result = mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400)).andReturn();
        var response = mapper.readTree(result.getResponse().getContentAsString());
        String message = response.get("message").asText();
        assertThat(message).startsWith("nickname: ");
        assertThat(message.substring("nickname: ".length()).split(", nickname: "))
                .containsExactlyInAnyOrderElementsOf(messages);
        assertThat(response.get("data").isNull()).isTrue();
        verifyNoInteractions(change, profiles, readPost, listPosts);
    }

    static Stream<Arguments> invalidNicknames() {
        return Stream.of(Arguments.of("{}", List.of(REQUIRED)), Arguments.of("{\"nickname\":null}", List.of(REQUIRED)),
                Arguments.of("{\"nickname\":\"\"}", List.of(REQUIRED, SIZE, PATTERN)),
                Arguments.of("{\"nickname\":\"A\"}", List.of(SIZE)),
                Arguments.of("{\"nickname\":\"" + "A".repeat(31) + "\"}", List.of(SIZE)),
                Arguments.of("{\"nickname\":\"닉 이름\"}", List.of(PATTERN)),
                Arguments.of("{\"nickname\":\"닉😀\"}", List.of(PATTERN)),
                Arguments.of("{\"nickname\":42}", List.of(PATTERN)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{\"nickname\":[]}", "{\"nickname\":{}}"})
    void given__malformed_or_nonobject_json__when__changing__then__return_original_safe_body_error_before_service(String json) throws Exception {
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(change, profiles, readPost, listPosts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token_and_invalid_body__when__changing__then__authentication_precedes_validation_and_service(String authorization, String message) throws Exception {
        var request = put("/api/v1/users/me/nickname").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).content("{");
        if (authorization != null) request.header("Authorization", authorization);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new UserNicknameController.Response<>(401, message, null)), JsonCompareMode.STRICT));
        verifyNoInteractions(change, profiles, readPost, listPosts);
    }

    static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER").expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        var otherKey = Keys.hmacShaKeyFor("f13-synthetic-other-signing-key-0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER").signWith(otherKey).compact();
        String refresh = Jwts.builder().expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."), Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + expired, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + forged, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + refresh, "토큰 파싱에 실패했습니다."));
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    void given__signed_invalid_identity__when__changing__then__claim_failure_prevents_service(Map<String, Object> claims) throws Exception {
        String token = Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."));
        verifyNoInteractions(change, profiles, readPost, listPosts);
    }

    @Test
    void given__authenticated_member_no_longer_exists__when__changing__then__preserve_missing_member_response() throws Exception {
        doThrow(new UserNotFoundException()).when(change).change(7, "새닉네임");
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(change).change(7, "새닉네임");
    }

    @Test
    void given__nickname_already_used__when__changing__then__return_original_conflict_without_success_fallback() throws Exception {
        doThrow(new NicknameDuplicateException()).when(change).change(7, "기존닉네임");
        mvc.perform(put("/api/v1/users/me/nickname").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"기존닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":409,\"message\":\"이미 사용 중인 닉네임입니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(change).change(7, "기존닉네임");
    }

    @Test
    void given__existing_profile_and_public_read__when__requesting__then__nickname_advice_and_authentication_do_not_intercept_them() throws Exception {
        when(profiles.require(7)).thenReturn(new UserProfile(7, "synthetic@fixture.invalid", "합성", "기존닉네임", "LOCAL", "ROLE_USER", null));
        when(listPosts.read()).thenReturn(List.of());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("기존닉네임"));
        mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verifyNoInteractions(change);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/users/me/nickname", "POST /api/v1/users/me/nickname",
            "PATCH /api/v1/users/me/nickname", "DELETE /api/v1/users/me/nickname", "HEAD /api/v1/users/me/nickname",
            "OPTIONS /api/v1/users/me/nickname", "PUT /api/v1/users/me/nickname/", "PUT /api/v1/users/me/nickname/extra",
            "PUT /api/v1/users/7/nickname", "PUT /api/v1/users/me/password", "PUT /api/users/me/nickname"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_service(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(change, profiles, readPost, listPosts);
    }
}
