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
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var login: SocialLogin

    @MockkBean
    lateinit var sessions: AuthSessions

    @MockkBean
    lateinit var tokens: JwtSessionTokens

    @Test
    fun `네이버 로그인이 켜져 있을 때__인가 흐름을 시작하면__클라이언트 콜백과 state만 담아 네이버로 리디렉션한다`() {
        // given
        // when
        val result = mvc.perform(get("/oauth2/authorization/naver"))
            .andExpect(status().is3xxRedirection())
            .andReturn()

        // then
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
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `인가 응답이 없는 네이버 콜백이 주어졌을 때__돌아오면__프런트 로그인 화면으로 실패 리디렉션한다`() {
        // given
        // when
        val result = mvc.perform(get("/login/oauth2/code/naver"))

        // then
        result
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("https://fixture.invalid/login?error=*"))
        confirmVerified(login, sessions, tokens)
    }

    @Test
    fun `네이버만 켜져 있을 때__구글 경로를 요청하면__기본 전체 거부 체인에 맡긴다`() {
        // given
        // when
        val result = mvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().isForbidden())
            .andReturn()

        // then
        assertThat(result.request.getSession(false)).isNull()
        confirmVerified(login, sessions, tokens)
    }
}
