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
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import java.sql.SQLException;
import java.util.List;
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

@WebMvcTest(controllers = FavoriteStatusController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {FavoriteStatusController.class, FavoriteStatusExceptionHandler.class,
        UserProfileController.class, UserProfileExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class FavoriteStatusHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean FavoriteStatusQuery favorites;
    @MockitoBean UserProfileQuery profiles;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void given__verified_principal_with_spoofed_ids__when__checking_favorite__then__use_principal_and_path_ids_and_return_exact_boolean_envelope(boolean exists) throws Exception {
        when(favorites.isFavorite(7, 42)).thenReturn(exists);

        mvc.perform(get("/api/v1/favorites/42/check").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").param("animalId", "999")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"animalId\":999}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":" + exists + "}", JsonCompareMode.STRICT));

        verify(favorites).isFavorite(7, 42);
        verifyNoMoreInteractions(favorites);
        verifyNoInteractions(profiles, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
    void given__zero_negative_or_long_boundary_animal_id__when__checking__then__original_long_reaches_query_without_new_positive_id_policy(long animalId) throws Exception {
        when(favorites.isFavorite(7, animalId)).thenReturn(false);

        mvc.perform(get("/api/v1/favorites/{animalId}/check", animalId)
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":false}", JsonCompareMode.STRICT));

        verify(favorites).isFavorite(7, animalId);
        verifyNoInteractions(profiles, readPost, listPosts);
    }

    @Test
    void given__two_principals_then_anonymous_request__when__checking__then__each_uses_its_own_identity_and_anonymous_cannot_reuse_it() throws Exception {
        when(favorites.isFavorite(7, 42)).thenReturn(true);
        when(favorites.isFavorite(8, 42)).thenReturn(false);
        for (long id : new long[]{7, 8}) {
            mvc.perform(get("/api/v1/favorites/42/check").header("Authorization", "Bearer " + F3TestInputs.token(id, "ROLE_USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(id == 7));
        }
        mvc.perform(get("/api/v1/favorites/42/check")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verify(favorites).isFavorite(7, 42);
        verify(favorites).isFavorite(8, 42);
        verifyNoMoreInteractions(favorites);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_token_and_malformed_id__when__checking__then__authentication_precedes_long_conversion_and_query(String authorization, String message) throws Exception {
        var request = get("/api/v1/favorites/abc/check").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new FavoriteStatusController.Response<>(401, message, null)), JsonCompareMode.STRICT));

        verifyNoInteractions(favorites, profiles, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808", "-9223372036854775809"})
    void given__authenticated_nonlong_or_overflowing_id__when__checking__then__preserve_original_runtime_500_before_query(String animalId) throws Exception {
        mvc.perform(get("/api/v1/favorites/{animalId}/check", animalId)
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        verifyNoInteractions(favorites, profiles, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    void given__sql_or_transaction_failure__when__checking__then__safe_500_is_not_false_or_authentication_failure_and_private_messages_are_not_logged(RuntimeException failure) throws Exception {
        when(favorites.isFavorite(7, 42)).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            mvc.perform(get("/api/v1/favorites/42/check").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-private", "Bearer ", "jdbc:");
            });
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
        verify(favorites).isFavorite(7, 42);
        verifyNoMoreInteractions(favorites);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<RuntimeException> queryFailures() {
        var cause = new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password");
        return Stream.of(new DataAccessResourceFailureException("synthetic-private-query", cause),
                new TransactionSystemException("Bearer synthetic-private-token", cause));
    }

    @Test
    void given__existing_profile_missing_and_public_post_list__when__requesting__then__favorite_advice_and_authentication_leave_their_contracts_intact() throws Exception {
        when(profiles.require(7)).thenThrow(new UserNotFoundException());
        when(listPosts.read()).thenReturn(List.of());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/posts/read").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());

        verifyNoInteractions(favorites);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/favorites", "GET /api/v1/favorites/42", "GET /api/v1/favorites/42/check/",
            "GET /api/v1/favorites/42/check/extra", "GET /api/favorites/42/check", "GET /api/v1/favorites/internal/42/check",
            "HEAD /api/v1/favorites/42/check", "OPTIONS /api/v1/favorites/42/check", "POST /api/v1/favorites/42/check",
            "PUT /api/v1/favorites/42/check", "POST /api/v1/favorites/42", "DELETE /api/v1/favorites/42"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_favorite_query(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(favorites, profiles, readPost, listPosts);
    }
}
