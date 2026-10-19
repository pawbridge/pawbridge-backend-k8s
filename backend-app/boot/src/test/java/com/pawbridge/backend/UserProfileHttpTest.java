package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.web.CommentReadController;
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.web.PostSearchController;
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(controllers = UserProfileController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {UserProfileController.class, UserProfileExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        PostSearchController.class, PostSearchExceptionHandler.class,
        CommentReadController.class, CommentReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserProfileHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean UserProfileQuery profiles;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;
    @MockitoBean SearchPosts searchPosts;
    @MockitoBean ListComments listComments;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__signed_identity_and_spoofed_headers_query_and_body__when__getting_me__then__return_only_that_ids_current_database_profile(String tokenRole) throws Exception {
        var profile = profile(7, "현재 닉네임", LocalDateTime.parse("2026-10-19T01:02:03.123456"));
        when(profiles.require(7)).thenReturn(profile);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, tokenRole))
                        .header("X-User-Id", "999").header("X-User-Email", "spoofed@fixture.invalid")
                        .header("X-User-Name", "위조 이름").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").param("role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(content().json("""
                        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                          "userId":7,"email":"current-7@fixture.invalid","name":"현재 이름7",
                          "nickname":"현재 닉네임","provider":"GOOGLE","role":"ROLE_SHELTER",
                          "createdAt":"2026-10-19T01:02:03.123456"}}
                        """, JsonCompareMode.STRICT));
        verify(profiles).require(7);
        verifyNoMoreInteractions(profiles);
        verifyNoInteractions(readPost, listPosts, searchPosts, listComments);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  \t ", "닉네임"})
    void given__nullable_join_date_and_raw_nickname__when__getting_me__then__preserve_null_key_empty_string_and_whitespace_in_exact_seven_fields(String nickname) throws Exception {
        when(profiles.require(7)).thenReturn(profile(7, nickname, null));

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.nickname").value(nickname))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"createdAt\":null")))
                .andExpect(content().json(mapper.writeValueAsString(new UserProfileController.Response<>(200,
                        "요청이 성공적으로 처리되었습니다.", profile(7, nickname, null))), JsonCompareMode.STRICT));
        verify(profiles).require(7);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token_with_spoofed_identity__when__getting_me__then__401_precedes_profile_query(String authorization, String message) throws Exception {
        var request = get("/api/v1/users/me").header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new UserProfileController.Response<>(401, message, null)), JsonCompareMode.STRICT));
        verifyNoInteractions(profiles, readPost, listPosts, searchPosts, listComments);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        var otherKey = Keys.hmacShaKeyFor("f12-synthetic-other-signing-key-only-012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(otherKey).compact();
        String refresh = Jwts.builder().expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + expired, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + forged, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + refresh, "토큰 파싱에 실패했습니다."));
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    void given__signed_invalid_identity__when__getting_me__then__claim_failure_prevents_profile_query(Map<String, Object> claims) throws Exception {
        String token = Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":401,\"message\":\"토큰 파싱에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(profiles, readPost, listPosts, searchPosts, listComments);
    }

    @Test
    void given__valid_id_with_unrelated_subject_name_and_nonstandard_string_role__when__getting_me__then__reuse_existing_identity_contract_without_new_role_or_subject_policy() throws Exception {
        String token = Jwts.builder().subject("another-member@fixture.invalid").claim("userId", 7)
                .claim("role", "ROLE_FUTURE").claim("name", Map.of("ignored", true))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(F3TestInputs.KEY).compact();
        when(profiles.require(7)).thenReturn(profile(7, "현재 닉네임", null));

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.email").value("current-7@fixture.invalid"))
                .andExpect(jsonPath("$.data.name").value("현재 이름7"))
                .andExpect(jsonPath("$.data.role").value("ROLE_SHELTER"));
        verify(profiles).require(7);
    }

    @Test
    void given__two_members_then_anonymous_request__when__getting_me__then__each_query_uses_its_own_principal_and_anonymous_cannot_reuse_it() throws Exception {
        when(profiles.require(anyLong())).thenAnswer(call -> profile(call.getArgument(0), "현재 닉네임", null));
        for (long id : new long[]{7, 8}) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value((int) id));
        }
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        verify(profiles).require(7);
        verify(profiles).require(8);
        verifyNoMoreInteractions(profiles);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__authenticated_member_no_longer_exists__when__getting_me__then__preserve_original_400_without_profile_fallback() throws Exception {
        when(profiles.require(7)).thenThrow(new UserNotFoundException());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(profiles).require(7);
        verifyNoMoreInteractions(profiles);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    void given__sql_transaction_or_unexpected_query_failure__when__getting_me__then__return_safe_500_outside_authentication_catch_without_sensitive_logs(RuntimeException failure) throws Exception {
        when(profiles.require(7)).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-private-token", "synthetic-private-email",
                        "synthetic-private-name", "synthetic-private-password", "synthetic-private-url", "Bearer ", "jdbc:postgresql:");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(profiles).require(7);
        verifyNoMoreInteractions(profiles);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<RuntimeException> queryFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("synthetic-private-email", privateFailure()),
                new TransactionSystemException("synthetic-private-name", privateFailure()), privateFailure());
    }

    private static RuntimeException privateFailure() {
        var failure = new IllegalStateException("Bearer synthetic-private-token; synthetic-private-email; synthetic-private-name",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/synthetic-private-url"));
        return failure;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/posts/read/42", "/api/v1/posts/read", "/api/v1/posts/search?keyword=test", "/api/v1/comments/posts/read/42"})
    void given__existing_public_read_with_invalid_bearer__when__requesting__then__profile_authentication_and_advice_do_not_intercept_it(String path) throws Exception {
        when(readPost.read(42)).thenThrow(new PostNotFoundException());
        when(listPosts.read()).thenReturn(List.of());
        when(searchPosts.search("test")).thenReturn(List.of());
        when(listComments.read(42)).thenReturn(List.of());

        var response = mvc.perform(get(path).header("Authorization", "Bearer invalid"));
        if (path.equals("/api/v1/posts/read/42")) {
            response.andExpect(status().isNotFound())
                    .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
            verify(readPost).read(42);
        } else {
            response.andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
                    .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data.length()").value(0));
        }
        verifyNoInteractions(profiles);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/users", "GET /api/v1/users/me/", "GET /api/v1/users/me/extra",
            "GET /api/v1/users/7", "GET /api/users/me", "GET /api/v1/admin/users/me",
            "GET /api/v1/users/internal/7/nickname", "GET /api/v1/users/internal/7/state", "GET /error",
            "HEAD /api/v1/users/me", "OPTIONS /api/v1/users/me", "POST /api/v1/users/me", "PUT /api/v1/users/me",
            "PATCH /api/v1/users/me", "DELETE /api/v1/users/me"})
    void given__unexposed_method_internal_api_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_profile_query(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(profiles, readPost, listPosts, searchPosts, listComments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42",
            "POST /api/v1/comments/posts/42", "PUT /api/v1/comments/12", "DELETE /api/v1/comments/12"})
    void given__existing_write_without_token__when__requesting__then__preserve_its_jwt_boundary(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(profiles, readPost, listPosts, searchPosts, listComments);
    }

    private static UserProfile profile(long id, String nickname, LocalDateTime created) {
        return new UserProfile(id, "current-" + id + "@fixture.invalid", "현재 이름" + id, nickname,
                "GOOGLE", "ROLE_SHELTER", created);
    }
}
