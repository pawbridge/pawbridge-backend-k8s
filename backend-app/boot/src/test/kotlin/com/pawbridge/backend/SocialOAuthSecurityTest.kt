package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.boot.configuration.SocialOAuthConfiguration
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = [
        "pawbridge.auth.google-enabled=true",
        "spring.security.oauth2.client.registration.google.client-id=synthetic-client",
        "spring.security.oauth2.client.registration.google.client-secret=synthetic-private-secret",
        "spring.security.oauth2.client.registration.google.redirect-uri=https://fixture.invalid/login/oauth2/code/google",
        "oauth2.redirect-uri=https://fixture.invalid/oauth/callback",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [SocialOAuthConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class SocialOAuthSecurityTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var login: SocialLogin

    @MockitoBean
    lateinit var sessions: AuthSessions

    @MockitoBean
    lateinit var tokens: JwtSessionTokens

    @Test
    fun given__explicit_google_configuration__when__starting_authorization__then__use_original_scopes_and_temporary_spring_state_only() {
        val result = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection()).andReturn()
        assertThat(result.request.getSession(false)).isNotNull()
        val target = result.response.redirectedUrl
        assertThat(target).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
            .contains("state=", "client_id=synthetic-client", "email", "profile", "prompt=select_account")
            .doesNotContain("openid", "synthetic-private-secret")
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__callback_without_authorization_response__when__returning__then__fail_before_remote_exchange_or_business() {
        mvc.perform(get("/login/oauth2/code/google"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("https://fixture.invalid/login?error=*"))
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__ordinary_rest_and_unregistered_oauth_paths__when__requesting__then__keep_existing_jwt_chain_and_default_deny_without_session() {
        val ordinary = mvc.perform(
            get("/api/v1/admin/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andReturn()
        assertThat(ordinary.request.getSession(false)).isNull()
        val unknown = mvc.perform(get("/oauth2/authorization/naver")).andExpect(status().isForbidden()).andReturn()
        assertThat(unknown.request.getSession(false)).isNull()
        mvc.perform(get("/login/oauth2/code/google/extra")).andExpect(status().isForbidden())
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__allowed_origin__when__preflighting_the_google_chain__then__answer_with_cors_headers_without_starting_oauth() {
        mvc.perform(
            options("/oauth2/authorization/google")
                .header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__disallowed_origin__when__preflighting_the_google_chain__then__reject_with_403() {
        mvc.perform(
            options("/oauth2/authorization/google")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__allowed_origin__when__starting_authorization__then__the_redirect_carries_the_cors_header() {
        mvc.perform(get("/oauth2/authorization/google").header("Origin", "https://www.pawbridge.kr"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__google_unspecified_or_disabled__when__assembling_configuration__then__register_no_oauth_client_or_security_chain() {
        for (flag in arrayOf("absent", "false")) {
            var runner = ApplicationContextRunner().withUserConfiguration(SocialOAuthConfiguration::class.java)
            if (flag == "false") {
                runner = runner.withPropertyValues("pawbridge.auth.google-enabled=false")
            }
            runner.run { context ->
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ClientRegistrationRepository::class.java)
                    .doesNotHaveBean(SecurityFilterChain::class.java)
                    .doesNotHaveBean(SocialLogin::class.java)
            }
        }
    }
}
