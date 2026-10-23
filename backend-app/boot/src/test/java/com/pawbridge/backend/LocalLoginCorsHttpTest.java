package com.pawbridge.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The local login lives in its own SecurityFilterChain (order 0), separate from the main chain.
 * A browser on https://www.pawbridge.kr calls https://api.pawbridge.kr, so this chain must answer
 * the CORS preflight itself, before LocalLoginFilter tries to authenticate an OPTIONS request.
 */
@WebMvcTest(
        properties = {"pawbridge.auth.local-enabled=true", "pawbridge.auth.sessions-enabled=true"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {
        LocalLoginConfiguration.class,
        ReadSecurityConfiguration.class,
        F3TestInputs.SecurityInputs.class})
class LocalLoginCorsHttpTest {
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String ALLOWED_ORIGIN = "https://www.pawbridge.kr";
    private static final String DISALLOWED_ORIGIN = "https://evil.example";
    private static final String PASSWORD = "correct-password";
    private static final String VALID_LOGIN_BODY = "{\"email\":\"member@example.invalid\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired MockMvc mvc;
    // Replaces the JDBC based LocalLogin bean, so no database is needed.
    @MockitoBean LocalLogin login;

    @BeforeEach
    void registerOneMember() {
        var account = new LocalLogin.Account(
                7L,
                "member@example.invalid",
                "Member",
                new BCryptPasswordEncoder().encode(PASSWORD),
                "ROLE_USER",
                null);
        when(login.findByEmail("member@example.invalid")).thenReturn(Optional.of(account));
        when(login.complete(any())).thenReturn(new AuthSessions.Tokens("synthetic-access", "synthetic-refresh"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.pawbridge.kr", "https://pawbridge.kr", "http://localhost:5173", "http://127.0.0.1:3000"})
    void given__allowed_origin__when__preflighting_login__then__200_with_cors_headers_and_no_authentication(String origin)
            throws Exception {
        mvc.perform(options(LOGIN_PATH)
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        verifyNoInteractions(login);
    }

    @Test
    void given__disallowed_origin__when__preflighting_login__then__403_without_cors_headers() throws Exception {
        mvc.perform(options(LOGIN_PATH)
                        .header("Origin", DISALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        verifyNoInteractions(login);
    }

    @Test
    void given__allowed_origin__when__logging_in__then__the_login_response_carries_the_cors_headers() throws Exception {
        mvc.perform(post(LOGIN_PATH)
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(jsonPath("$.data.accessToken").value("synthetic-access"));
    }

    @Test
    void given__allowed_origin__when__credentials_are_wrong__then__the_401_still_carries_the_cors_headers() throws Exception {
        mvc.perform(post(LOGIN_PATH)
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.invalid\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    void given__disallowed_origin__when__logging_in_without_preflight__then__keep_the_normal_response_without_cors_headers()
            throws Exception {
        mvc.perform(post(LOGIN_PATH)
                        .header("Origin", DISALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
