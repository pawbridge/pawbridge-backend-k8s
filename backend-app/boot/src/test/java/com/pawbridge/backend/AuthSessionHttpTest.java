package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.web.AuthSessionController;
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(controllers = AuthSessionController.class, properties = "pawbridge.auth.sessions-enabled=true",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(classes = {AuthSessionHttpTest.Controller.class, AuthSessionExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthSessionHttpTest {
    static final String ORIGIN = "https://www.pawbridge.kr";
    static final String PRESENTED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final String NEXT = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    static final String CLEAR_COOKIE = "__Secure-pb_refresh=; Path=/api/v1/auth; Max-Age=0; "
            + "Expires=Thu, 1 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict";

    @Autowired MockMvc mvc;
    @MockitoBean AuthSessions sessions;

    static class Controller {
        @Bean
        AuthSessionController authSessionController(AuthSessions sessions) {
            return new AuthSessionController(sessions, List.of(ORIGIN, "https://pawbridge.kr"));
        }
    }

    private static MockHttpServletRequestBuilder browser(String path) {
        return post(path).header("Origin", ORIGIN).contentType("application/json").content("{}");
    }

    @Test
    void given__an_allowed_page_with_the_refresh_cookie__when__refreshing__then__return_only_the_access_token_and_rotate_the_cookie() throws Exception {
        when(sessions.refresh(PRESENTED)).thenReturn(new AuthSessions.Rotated("synthetic-access",
                new IssuedRefreshToken(NEXT, 1_209_600)));

        var result = mvc.perform(browser("/api/v1/auth/refresh")
                        .cookie(new Cookie("__Secure-pb_refresh", PRESENTED))
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"토큰이 재발급되었습니다.\",\"data\":{\"accessToken\":\"synthetic-access\"}}",
                        JsonCompareMode.STRICT))
                .andReturn();

        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("__Secure-pb_refresh=" + NEXT + "; Path=/api/v1/auth; Max-Age=1209600; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(NEXT).doesNotContain("refreshToken");
        verify(sessions).refresh(PRESENTED);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void given__a_concurrent_refresh_already_rotated_the_cookie__when__refreshing__then__return_access_without_touching_the_cookie() throws Exception {
        when(sessions.refresh(PRESENTED)).thenReturn(new AuthSessions.AccessOnly("synthetic-access"));

        mvc.perform(browser("/api/v1/auth/refresh").cookie(new Cookie("__Secure-pb_refresh", PRESENTED)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"토큰이 재발급되었습니다.\",\"data\":{\"accessToken\":\"synthetic-access\"}}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void given__a_legacy_refresh_body_without_cookie__when__refreshing__then__ignore_the_body_and_reject_as_missing() throws Exception {
        when(sessions.refresh(null)).thenThrow(new AuthSessionRejectedException(Reason.MISSING));

        mvc.perform(browser("/api/v1/auth/refresh").content("{\"refreshToken\":\"" + PRESENTED + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
                .andExpect(content().json("{\"code\":401,\"message\":\"리프레시 토큰을 찾을 수 없습니다.\",\"data\":null}",
                        JsonCompareMode.STRICT));
        verify(sessions).refresh(null);
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = {"INVALID", "REUSED"})
    void given__an_invalid_or_reused_cookie__when__refreshing__then__401_and_clear_the_cookie(Reason reason) throws Exception {
        when(sessions.refresh(PRESENTED)).thenThrow(new AuthSessionRejectedException(reason));

        mvc.perform(browser("/api/v1/auth/refresh").cookie(new Cookie("__Secure-pb_refresh", PRESENTED)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
                .andExpect(content().json("{\"code\":401,\"message\":\"유효하지 않은 토큰입니다.\",\"data\":null}",
                        JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-origin", "other-origin", "wildcard-sibling", "null-origin", "missing-type",
            "form-type", "text-type", "malformed-type"})
    void given__a_request_no_allowed_page_could_send__when__refreshing_or_logging_out__then__403_before_the_session_service(
            String kind) throws Exception {
        for (String path : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            var request = post(path).cookie(new Cookie("__Secure-pb_refresh", PRESENTED)).content("{}");
            switch (kind) {
                case "missing-origin" -> request.contentType("application/json");
                case "other-origin" -> request.header("Origin", "https://evil.example").contentType("application/json");
                case "wildcard-sibling" -> request.header("Origin", "https://shop.pawbridge.kr").contentType("application/json");
                case "null-origin" -> request.header("Origin", "null").contentType("application/json");
                case "missing-type" -> request.header("Origin", ORIGIN);
                case "form-type" -> request.header("Origin", ORIGIN).contentType("application/x-www-form-urlencoded");
                case "text-type" -> request.header("Origin", ORIGIN).contentType("text/plain");
                default -> request.header("Origin", ORIGIN).header("Content-Type", "application/");
            }
            mvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}",
                            JsonCompareMode.STRICT));
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void given__json_with_a_charset_from_the_second_allowed_origin__when__refreshing__then__accept_it() throws Exception {
        when(sessions.refresh(PRESENTED)).thenReturn(new AuthSessions.AccessOnly("synthetic-access"));

        mvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "https://pawbridge.kr")
                        .contentType("application/json;charset=UTF-8")
                        .cookie(new Cookie("__Secure-pb_refresh", PRESENTED)))
                .andExpect(status().isOk());
    }

    @Test
    void given__an_expired_access_token_or_none__when__logging_out_repeatedly__then__revoke_by_cookie_and_always_clear_it() throws Exception {
        String expired = io.jsonwebtoken.Jwts.builder()
                .claim("userId", 7)
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(F3TestInputs.KEY)
                .compact();

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(browser("/api/v1/auth/logout")
                            .cookie(new Cookie("__Secure-pb_refresh", PRESENTED))
                            .header("Authorization", "Bearer " + expired))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
                    .andExpect(content().json("{\"code\":200,\"message\":\"로그아웃되었습니다.\",\"data\":null}",
                            JsonCompareMode.STRICT));
        }
        mvc.perform(browser("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", CLEAR_COOKIE));

        verify(sessions, times(2)).logout(PRESENTED);
        verify(sessions).logout(null);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void given__a_database_failure__when__refreshing__then__safe_500_that_keeps_the_cookie_and_logs_no_details(CapturedOutput output) throws Exception {
        when(sessions.refresh(PRESENTED)).thenThrow(new TransactionSystemException("synthetic-private-token",
                new RuntimeException("synthetic-private-sql")));

        mvc.perform(browser("/api/v1/auth/refresh").cookie(new Cookie("__Secure-pb_refresh", PRESENTED)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        doThrow(new TransactionSystemException("synthetic-private-token")).when(sessions).logout(PRESENTED);
        mvc.perform(browser("/api/v1/auth/logout").cookie(new Cookie("__Secure-pb_refresh", PRESENTED)))
                .andExpect(status().isInternalServerError());

        assertThat(output.getAll()).contains("Auth session request failed (TransactionSystemException)")
                .doesNotContain("synthetic-private-token", "synthetic-private-sql", PRESENTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/auth/refresh", "POST /api/v1/auth/refresh/extra", "POST /api/v1/auth/logout/extra",
            "POST /api/v1/auth/login", "POST /api/auth/logout"})
    void given__adjacent_or_unported_auth_route__when__requesting__then__deny_before_session_service(String spec) throws Exception {
        var split = spec.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(split[0]), split[1]).header("X-User-Id", "7").header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions);
    }
}

@WebMvcTest(controllers = AuthSessionController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(classes = {AuthSessionHttpTest.Controller.class, AuthSessionExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AuthSessionDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthSessions sessions;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void given__default_off_despite_cookie_and_token__when__posting__then__preserve_default403_without_service(String path) throws Exception {
        mvc.perform(post(path)
                        .header("Origin", AuthSessionHttpTest.ORIGIN)
                        .contentType("application/json")
                        .cookie(new Cookie("__Secure-pb_refresh", AuthSessionHttpTest.PRESENTED))
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions);
    }
}
