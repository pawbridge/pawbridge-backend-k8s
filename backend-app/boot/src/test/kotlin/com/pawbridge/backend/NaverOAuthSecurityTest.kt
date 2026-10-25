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
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = [
        "pawbridge.auth.naver-enabled=true",
        "spring.security.oauth2.client.registration.naver.client-id=synthetic-naver-client",
        "spring.security.oauth2.client.registration.naver.client-secret=synthetic-naver-secret",
        "spring.security.oauth2.client.registration.naver.redirect-uri=https://fixture.invalid/login/oauth2/code/naver",
        "oauth2.redirect-uri=https://fixture.invalid/oauth/callback",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [SocialOAuthConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class NaverOAuthSecurityTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var login: SocialLogin

    @MockitoBean
    lateinit var sessions: AuthSessions

    @MockitoBean
    lateinit var tokens: JwtSessionTokens

    @Test
    fun given__naver_enabled__when__starting_authorization__then__redirect_to_naver_with_client_callback_and_state_only() {
        val result = mvc.perform(get("/oauth2/authorization/naver"))
            .andExpect(status().is3xxRedirection())
            .andReturn()

        assertThat(result.request.getSession(false)).isNotNull()
        assertThat(result.response.redirectedUrl)
            .startsWith("https://nid.naver.com/oauth2.0/authorize")
            .contains(
                "response_type=code",
                "client_id=synthetic-naver-client",
                "state=",
                "redirect_uri=https://fixture.invalid/login/oauth2/code/naver",
            )
            .doesNotContain("synthetic-naver-secret")
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__naver_callback_without_authorization_response__when__returning__then__fail_to_frontend_login() {
        mvc.perform(get("/login/oauth2/code/naver"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("https://fixture.invalid/login?error=*"))
        verifyNoInteractions(login, sessions, tokens)
    }

    @Test
    fun given__only_naver_enabled__when__requesting_google_paths__then__leave_them_to_the_default_deny_chain() {
        val result = mvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().isForbidden())
            .andReturn()
        assertThat(result.request.getSession(false)).isNull()
        verifyNoInteractions(login, sessions, tokens)
    }
}
