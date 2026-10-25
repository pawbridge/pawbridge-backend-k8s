package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

/**
 * The local login lives in its own SecurityFilterChain (order 0), separate from the main chain.
 * A browser on https://www.pawbridge.kr calls https://api.pawbridge.kr, so this chain must answer
 * the CORS preflight itself, before LocalLoginFilter tries to authenticate an OPTIONS request.
 */
@WebMvcTest(
    properties = ["pawbridge.auth.local-enabled=true", "pawbridge.auth.sessions-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [LocalLoginConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class LocalLoginCorsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    // Replaces the JDBC based LocalLogin bean, so no database is needed.
    @MockitoBean
    lateinit var login: LocalLogin

    @BeforeEach
    fun registerOneMember() {
        val account = LocalLogin.Account(
            7L,
            "member@example.invalid",
            "Member",
            BCryptPasswordEncoder().encode(PASSWORD),
            "ROLE_USER",
            null,
        )
        whenever(login.findByEmail("member@example.invalid")).thenReturn(Optional.of(account))
        whenever(login.complete(anyNonNull<LocalLogin.Account>())).thenReturn(
            LocalLogin.CompletedLogin(
                "synthetic-access",
                IssuedRefreshToken("DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", 60),
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://www.pawbridge.kr", "https://pawbridge.kr", "http://localhost:5173", "http://127.0.0.1:3000"])
    fun given__allowed_origin__when__preflighting_login__then__200_with_cors_headers_and_no_authentication(origin: String) {
        mvc.perform(
            options(LOGIN_PATH)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", origin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))

        verifyNoInteractions(login)
    }

    @Test
    fun given__disallowed_origin__when__preflighting_login__then__403_without_cors_headers() {
        mvc.perform(
            options(LOGIN_PATH)
                .header("Origin", DISALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))

        verifyNoInteractions(login)
    }

    @Test
    fun given__allowed_origin__when__logging_in__then__the_login_response_carries_the_cors_headers() {
        mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_LOGIN_BODY),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(jsonPath("$.data.accessToken").value("synthetic-access"))
    }

    @Test
    fun given__allowed_origin__when__credentials_are_wrong__then__the_401_still_carries_the_cors_headers() {
        mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@example.invalid\",\"password\":\"wrong\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun given__disallowed_origin__when__logging_in_without_preflight__then__keep_the_normal_response_without_cors_headers() {
        mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", DISALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_LOGIN_BODY),
        )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    companion object {
        private const val LOGIN_PATH = "/api/v1/auth/login"
        private const val ALLOWED_ORIGIN = "https://www.pawbridge.kr"
        private const val DISALLOWED_ORIGIN = "https://evil.example"
        private const val PASSWORD = "correct-password"
        private const val VALID_LOGIN_BODY = "{\"email\":\"member@example.invalid\",\"password\":\"" + PASSWORD + "\"}"
    }
}
