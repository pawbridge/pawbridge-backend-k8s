package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.boot.configuration.SocialOAuthConfiguration
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import io.mockk.confirmVerified
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var login: SocialLogin

    @MockkBean
    lateinit var sessions: AuthSessions

    @MockkBean
    lateinit var tokens: JwtSessionTokens

    @Test
    fun `명시적 구글 설정이 주어졌을 때__인가 흐름을 시작하면__기존 스코프와 임시 Spring state만 사용한다`() {
        // given
        // when
        val result = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection()).andReturn()

        // then
        assertThat(result.request.getSession(false)).isNotNull()
        val target = result.response.redirectedUrl
        assertThat(target).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
            .contains("state=", "client_id=synthetic-client", "email", "profile", "prompt=select_account")
            .doesNotContain("openid", "synthetic-private-secret")
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `인가 응답이 없는 콜백이 주어졌을 때__돌아오면__원격 교환과 비즈니스 호출 전에 실패한다`() {
        // given
        // when
        val result = mvc.perform(get("/login/oauth2/code/google"))

        // then
        result
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("https://fixture.invalid/login?error=*"))
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `일반 REST 경로와 등록되지 않은 OAuth 경로가 주어졌을 때__요청하면__기존 JWT 체인과 기본 거부를 세션 없이 유지한다`() {
        // given
        val token = F3TestInputs.token(7, "ROLE_USER")

        // when
        val ordinary = mvc.perform(get("/api/v1/admin/posts/42").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden())
            .andReturn()
        val unknown = mvc.perform(get("/oauth2/authorization/naver")).andExpect(status().isForbidden()).andReturn()
        val extra = mvc.perform(get("/login/oauth2/code/google/extra"))

        // then
        assertThat(ordinary.request.getSession(false)).isNull()
        assertThat(unknown.request.getSession(false)).isNull()
        extra.andExpect(status().isForbidden())
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `허용된 출처가 주어졌을 때__구글 체인을 프리플라이트하면__OAuth 시작 없이 CORS 헤더로 응답한다`() {
        // given
        // when
        val result = mvc.perform(
            options("/oauth2/authorization/google")
                .header("Origin", "https://www.pawbridge.kr")
                .header("Access-Control-Request-Method", "GET"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `허용되지 않은 출처가 주어졌을 때__구글 체인을 프리플라이트하면__403으로 거부한다`() {
        // given
        // when
        val result = mvc.perform(
            options("/oauth2/authorization/google")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `허용된 출처가 주어졌을 때__인가 흐름을 시작하면__리디렉션에 CORS 헤더가 실린다`() {
        // given
        // when
        val result = mvc.perform(get("/oauth2/authorization/google").header("Origin", "https://www.pawbridge.kr"))

        // then
        result
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://www.pawbridge.kr"))
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `구글이 지정되지 않았거나 꺼져 있을 때__설정을 구성하면__OAuth 클라이언트와 보안 체인을 등록하지 않는다`() {
        // given
        for (flag in arrayOf("absent", "false")) {
            var runner = ApplicationContextRunner().withUserConfiguration(SocialOAuthConfiguration::class.java)
            if (flag == "false") {
                runner = runner.withPropertyValues("pawbridge.auth.google-enabled=false")
            }

            // when
            runner.run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ClientRegistrationRepository::class.java)
                    .doesNotHaveBean(SecurityFilterChain::class.java)
                    .doesNotHaveBean(SocialLogin::class.java)
            }
        }
    }
}
